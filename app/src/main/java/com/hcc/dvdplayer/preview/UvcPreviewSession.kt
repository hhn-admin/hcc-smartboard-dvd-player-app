package com.hcc.dvdplayer.preview

import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.Surface
import com.hcc.dvdplayer.logic.PreviewSizeChooser
import com.hcc.dvdplayer.logic.VideoMode
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.UVCParam
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opens one USB video device through UVCAndroid (libusb + libuvc).
 *
 * Camera2 is preferable when the board's camera HAL enumerates the dongle, but a
 * lot of smart-board images ship without an external-camera provider. libuvc talks
 * to the UVC interface directly, which is what still works on those images.
 * The bandwidth quirk is on because cheap HDMI capture sticks often advertise a
 * dwMaxPayloadTransferSize that makes MJPEG negotiation fail without it.
 * libuvc claims the video interfaces only, so the UAC interfaces stay available
 * to Android or to [com.hcc.dvdplayer.audio.UsbUacCapture]. This path does not
 * use the camera permission.
 */
class UvcPreviewSession(
    private val targetDeviceName: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onPreviewStarted(width: Int, height: Int)
        fun onUsbPermissionDenied()
        fun onDetached()
        fun onFailed(message: String)
    }

    private val helper = CameraHelper()
    private val selected = AtomicBoolean(false)
    private var surface: Surface? = null
    private var cameraReady = false
    private var bound = false
    private var released = false
    private var attempt = 0
    private var previewWidth = 16
    private var previewHeight = 9

    private val callback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            if (device.deviceName == targetDeviceName) selectOnce(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            if (released || device.deviceName != targetDeviceName) return
            openCurrentAttempt()
        }

        override fun onCameraOpen(device: UsbDevice) {
            if (released || device.deviceName != targetDeviceName) return
            cameraReady = true
            applyPreferredSize()
            bindSurface()
        }

        override fun onCameraClose(device: UsbDevice) {
            bound = false
        }

        override fun onDeviceClose(device: UsbDevice) = Unit

        override fun onDetach(device: UsbDevice) {
            if (device.deviceName == targetDeviceName) listener.onDetached()
        }

        override fun onCancel(device: UsbDevice) {
            if (device.deviceName == targetDeviceName) listener.onUsbPermissionDenied()
        }

        override fun onError(device: UsbDevice, e: com.herohan.uvcapp.CameraException) {
            if (released || device.deviceName != targetDeviceName) return
            Log.w(TAG, "UVC error: ${e.message}")
            if (!cameraReady && attempt <= FALLBACKS.size) {
                attempt += 1
                openCurrentAttempt()
            } else if (!bound) {
                listener.onFailed(e.message ?: "Could not open the USB video device")
            }
        }
    }

    fun start() {
        helper.setStateCallback(callback)
        // USBMonitor reports devices that are already plugged in through onAttach.
        // Selecting here as well covers a callback that never names our device.
        helper.deviceList?.firstOrNull { it.deviceName == targetDeviceName }?.let(::selectOnce)
    }

    fun onSurfaceReady(newSurface: Surface) {
        surface = newSurface
        if (cameraReady) bindSurface()
    }

    fun onSurfaceDestroyed(gone: Surface) {
        if (surface !== gone) return
        try {
            helper.removeSurface(gone)
        } catch (error: Exception) {
            Log.w(TAG, "removeSurface", error)
        }
        surface = null
        bound = false
    }

    fun release() {
        released = true
        val current = surface
        surface = null
        try {
            if (current != null) helper.removeSurface(current)
        } catch (error: Exception) {
            Log.w(TAG, "removeSurface", error)
        }
        try {
            helper.setStateCallback(null)
        } catch (error: Exception) {
            Log.w(TAG, "clear callback", error)
        }
        try {
            helper.release()
        } catch (error: Exception) {
            Log.w(TAG, "release", error)
        }
    }

    private fun selectOnce(device: UsbDevice) {
        if (!selected.compareAndSet(false, true)) return
        Log.i(TAG, "Requesting USB permission for ${device.deviceName}")
        helper.selectDevice(device)
    }

    private fun openCurrentAttempt() {
        if (released) return
        val param = if (attempt == 0) {
            UVCParam().apply { quirks = UVCCamera.UVC_QUIRK_FIX_BANDWIDTH }
        } else {
            val index = attempt - 1
            if (index >= FALLBACKS.size) {
                listener.onFailed("Could not open the USB video device")
                return
            }
            val (width, height) = FALLBACKS[index]
            val size = Size(
                UVCCamera.UVC_VS_FRAME_MJPEG,
                width,
                height,
                PreviewSizeChooser.TARGET_FPS,
                arrayListOf(PreviewSizeChooser.TARGET_FPS),
            )
            UVCParam(size, UVCCamera.UVC_QUIRK_FIX_BANDWIDTH)
        }
        try {
            helper.openCamera(param)
        } catch (error: Exception) {
            Log.e(TAG, "openCamera", error)
            listener.onFailed(error.message ?: "Could not open the USB video device")
        }
    }

    private fun applyPreferredSize() {
        val reported = (helper.supportedSizeList ?: emptyList()).map { size ->
            VideoMode(
                type = size.type,
                width = size.width,
                height = size.height,
                fps = size.fps,
                fpsOptions = size.fpsList ?: emptyList(),
            )
        }
        val chosen = PreviewSizeChooser.choose(reported) ?: return
        previewWidth = chosen.width
        previewHeight = chosen.height
        val fpsList = ArrayList<Int>(chosen.fpsOptions.size.coerceAtLeast(1))
        if (chosen.fpsOptions.isEmpty()) fpsList += chosen.fps else fpsList += chosen.fpsOptions
        try {
            helper.previewSize = Size(chosen.type, chosen.width, chosen.height, chosen.fps, fpsList)
            Log.i(TAG, "UVC preview ${chosen.width}x${chosen.height}@${chosen.fps} type=${chosen.type}")
        } catch (error: Exception) {
            Log.w(TAG, "setPreviewSize fell back to the device default", error)
        }
    }

    private fun bindSurface() {
        val target = surface ?: return
        if (bound || released || !cameraReady) return
        bound = true
        helper.addSurface(target, false)
        helper.startPreview()
        listener.onPreviewStarted(previewWidth, previewHeight)
    }

    companion object {
        private const val TAG = "HdmiIn"
        private val FALLBACKS = listOf(
            1920 to 1080,
            1280 to 720,
            640 to 480,
            720 to 480,
        )
    }
}
