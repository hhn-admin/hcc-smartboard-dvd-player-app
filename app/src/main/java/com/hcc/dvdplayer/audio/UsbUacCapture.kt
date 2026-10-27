package com.hcc.dvdplayer.audio

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.hcc.dvdplayer.logic.CaptureSourceFilter
import com.hcc.dvdplayer.logic.ExclusionPolicy
import com.hcc.dvdplayer.logic.UacDescriptorParser

/**
 * Reads the capture card's UAC streaming interface directly.
 *
 * UVCAndroid claims the video control and video streaming interfaces only.
 * On some board images that still makes Android drop the USB input. This path
 * claims the audio interfaces (never video, never the SMART webcam) and pulls
 * the isochronous PCM itself.
 */
internal class UsbUacCapture(
    context: Context,
    private val deviceName: String,
    private val policy: ExclusionPolicy = ExclusionPolicy.DEFAULT,
) {
    class Session(
        val sampleRate: Int,
        val channelCount: Int,
        val summary: String,
        private val connection: UsbDeviceConnection,
        private val claimed: List<UsbInterface>,
        private val reader: IsochronousReader,
    ) {
        fun read(buffer: ByteArray): Int = reader.read(buffer, READ_TIMEOUT_MS)

        fun close() {
            try {
                reader.stop()
            } catch (error: Exception) {
                Log.w(TAG, "iso stop", error)
            }
            claimed.asReversed().forEach { iface ->
                try {
                    connection.releaseInterface(iface)
                } catch (error: Exception) {
                    Log.w(TAG, "releaseInterface", error)
                }
            }
            try {
                connection.close()
            } catch (error: Exception) {
                Log.w(TAG, "close", error)
            }
        }
    }

    private val appContext = context.applicationContext

    fun open(): Session? {
        val manager = appContext.getSystemService(UsbManager::class.java) ?: return null
        val device = manager.deviceList.values.firstOrNull { it.deviceName == deviceName } ?: return null
        if (CaptureSourceFilter.isExcludedUsb(
                device.vendorId,
                quiet { device.productName },
                quiet { device.manufacturerName },
                policy = policy,
            )
        ) {
            Log.w(TAG, "refusing USB audio on an excluded device ${device.deviceName}")
            return null
        }
        val connection = try {
            manager.openDevice(device)
        } catch (error: SecurityException) {
            Log.w(TAG, "openDevice", error)
            null
        } ?: return null
        val claimed = ArrayList<UsbInterface>()
        val reader = IsochronousReader()
        try {
            val raw = connection.rawDescriptors
            val stream = if (raw == null || raw.isEmpty()) null else UacDescriptorParser.findStream(raw)
            if (stream == null || stream.maxPacketSize <= 0) {
                Log.w(TAG, "no UAC streaming interface on ${device.deviceName}")
                connection.close()
                return null
            }
            val control = findInterface(device, stream.controlInterface, 0)
            if (control != null && control.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                if (!connection.claimInterface(control, true)) {
                    Log.w(TAG, "claim audio control ${control.id} failed")
                } else {
                    claimed += control
                }
            }
            val streaming = findInterface(device, stream.streamingInterface, stream.alternateSetting)
                ?: findInterface(device, stream.streamingInterface, 0)
            if (streaming == null || streaming.interfaceClass != UsbConstants.USB_CLASS_AUDIO) {
                Log.w(TAG, "audio streaming interface ${stream.streamingInterface} is not on ${device.deviceName}")
                release(connection, claimed)
                return null
            }
            val alreadyClaimed = claimed.any { it.id == streaming.id }
            if (!alreadyClaimed && !connection.claimInterface(streaming, true)) {
                Log.w(TAG, "claim audio streaming ${streaming.id} failed")
                release(connection, claimed)
                return null
            }
            if (!alreadyClaimed) claimed += streaming
            if (streaming.alternateSetting == stream.alternateSetting) {
                connection.setInterface(streaming)
            }
            unmute(connection, stream)
            setSampleRate(connection, stream)
            val packets = PACKETS_PER_URB
            val started = try {
                reader.start(
                    fd = connection.fileDescriptor,
                    endpoint = stream.endpointAddress,
                    packetSize = stream.maxPacketSize,
                    packets = packets,
                    urbCount = URB_COUNT,
                    interfaceNumber = stream.streamingInterface,
                    alternate = stream.alternateSetting,
                )
            } catch (error: UnsatisfiedLinkError) {
                Log.e(TAG, "uaciso", error)
                false
            } catch (error: ExceptionInInitializerError) {
                Log.e(TAG, "uaciso", error)
                false
            }
            if (!started) {
                release(connection, claimed)
                return null
            }
            Log.i(TAG, "direct UAC ${stream.summary()} on ${device.deviceName}")
            return Session(
                sampleRate = stream.playbackSampleRate,
                channelCount = stream.playbackChannels,
                summary = stream.summary(),
                connection = connection,
                claimed = claimed.toList(),
                reader = reader,
            )
        } catch (error: Exception) {
            Log.e(TAG, "direct UAC", error)
            try {
                reader.stop()
            } catch (_: Exception) {
            }
            release(connection, claimed)
            return null
        }
    }

    private fun unmute(connection: UsbDeviceConnection, stream: UacDescriptorParser.Stream) {
        val unit = stream.featureUnitId ?: return
        if (stream.controlInterface < 0) return
        val payload = byteArrayOf(0)
        val index = (unit shl 8) or (stream.controlInterface and 0xff)
        val wrote = connection.controlTransfer(0x21, 0x01, 0x0100, index, payload, 1, 200)
        Log.i(TAG, "unmute unit=$unit -> $wrote")
    }

    private fun setSampleRate(connection: UsbDeviceConnection, stream: UacDescriptorParser.Stream) {
        val rate = stream.sampleRate
        val payload = byteArrayOf(
            (rate and 0xff).toByte(),
            ((rate shr 8) and 0xff).toByte(),
            ((rate shr 16) and 0xff).toByte(),
        )
        val wrote = connection.controlTransfer(0x22, 0x01, 0x0100, stream.endpointAddress, payload, 3, 200)
        Log.i(TAG, "rate $rate -> $wrote")
    }

    private fun findInterface(device: UsbDevice, number: Int, alternate: Int): UsbInterface? {
        if (number < 0) return null
        for (index in 0 until device.interfaceCount) {
            val iface = device.getInterface(index)
            if (iface.id == number && iface.alternateSetting == alternate) return iface
        }
        return null
    }

    private fun release(connection: UsbDeviceConnection, claimed: List<UsbInterface>) {
        claimed.asReversed().forEach { iface ->
            try {
                connection.releaseInterface(iface)
            } catch (_: Exception) {
            }
        }
        try {
            connection.close()
        } catch (_: Exception) {
        }
    }

    private fun quiet(read: () -> String?): String? {
        return try {
            read()?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        private const val TAG = "HdmiIn"
        private const val PACKETS_PER_URB = 8
        private const val URB_COUNT = 4
        private const val READ_TIMEOUT_MS = 200
    }
}
