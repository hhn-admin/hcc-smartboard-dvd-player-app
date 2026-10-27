package com.hcc.dvdplayer.audio

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.hcc.dvdplayer.logic.AudioDiagnosticsFormatter
import com.hcc.dvdplayer.logic.CaptureSourceFilter
import com.hcc.dvdplayer.logic.ExclusionPolicy

/**
 * Collects the USB and audio-input snapshot shown when the status line is
 * long-pressed. Names that Android hides behind a missing USB permission are
 * left blank instead of failing the screen.
 */
class PlaybackDiagnostics(context: Context) {
    private val appContext = context.applicationContext

    fun render(
        versionName: String,
        playingDeviceName: String?,
        productHint: String?,
        directUsb: String,
        picture: String,
        policy: ExclusionPolicy = ExclusionPolicy.DEFAULT,
    ): String {
        val usbDevices = connectedUsbDevices()
        val identities = usbDevices.map { device ->
            CaptureSourceFilter.UsbIdentity(
                vendorId = device.vendorId,
                deviceName = device.deviceName,
                productName = quiet { device.productName },
                manufacturerName = quiet { device.manufacturerName },
                productId = device.productId,
                hasAudioInterface = hasAudio(device),
            )
        }
        val audio = appContext.getSystemService(AudioManager::class.java)
        val inputs = audio?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.map { info ->
            val identity = CaptureSourceFilter.AudioInputIdentity(
                productName = info.productName?.toString(),
                address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.address else null,
            )
            val decision = if (AudioLoopback.isUsbInput(info.type)) {
                CaptureSourceFilter.audioDecision(
                    identity,
                    identities,
                    productHint,
                    playingDeviceName,
                    policy,
                )
            } else {
                "blocked: not a USB input"
            }
            AudioDiagnosticsFormatter.InputLine(
                typeName = typeName(info.type),
                productName = identity.productName,
                address = identity.address,
                rates = info.sampleRates.filter { it > 0 },
                channels = info.channelCounts.filter { it > 0 },
                decision = decision,
            )
        }.orEmpty()
        val devices = usbDevices.map { device ->
            AudioDiagnosticsFormatter.UsbDeviceLine(
                vendorId = device.vendorId,
                productId = device.productId,
                manufacturer = quiet { device.manufacturerName },
                product = quiet { device.productName },
                deviceName = device.deviceName,
                hasAudio = hasAudio(device),
                playing = device.deviceName == playingDeviceName,
                interfaces = interfacesOf(device),
            )
        }
        return AudioDiagnosticsFormatter.format(
            versionName = versionName,
            playingDeviceName = playingDeviceName,
            productHint = productHint,
            directUsb = directUsb,
            devices = devices,
            inputs = inputs,
            picture = picture,
        )
    }

    private fun connectedUsbDevices(): List<UsbDevice> {
        val manager = appContext.getSystemService(UsbManager::class.java) ?: return emptyList()
        return manager.deviceList.values.sortedWith(compareBy({ it.vendorId }, { it.productId }, { it.deviceName }))
    }

    private fun hasAudio(device: UsbDevice): Boolean {
        return try {
            (0 until device.interfaceCount).any { index ->
                device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun interfacesOf(device: UsbDevice): List<AudioDiagnosticsFormatter.InterfaceLine> {
        return try {
            (0 until device.interfaceCount).map { index ->
                val iface = device.getInterface(index)
                val endpoints = (0 until iface.endpointCount).map { endpointIndex ->
                    val endpoint = iface.getEndpoint(endpointIndex)
                    AudioDiagnosticsFormatter.EndpointLine(
                        address = endpoint.address,
                        transfer = transferName(endpoint.type),
                        direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "in" else "out",
                        maxPacket = endpoint.maxPacketSize,
                    )
                }
                AudioDiagnosticsFormatter.InterfaceLine(
                    number = iface.id,
                    alternate = iface.alternateSetting,
                    clazz = iface.interfaceClass,
                    subclass = iface.interfaceSubclass,
                    protocol = iface.interfaceProtocol,
                    endpoints = endpoints,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun quiet(read: () -> String?): String? {
        return try {
            read()?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun transferName(type: Int): String {
        return when (type) {
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "iso"
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "int"
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
            else -> "xfer$type"
        }
    }

    private fun typeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                "USB headset"
            } else {
                "type $type"
            }
        }
    }
}
