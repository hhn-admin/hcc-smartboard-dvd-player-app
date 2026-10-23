package com.hcc.dvdplayer.preview

import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
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
        fun onVideoSize(width: Int, height: Int) {}
        fun onUsbPermissionDenied()
        fun onDetached()
        fun onFailed(message: String)
    }

    private val helper = CameraHelper()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val selected = AtomicBoolean(false)
    private var surface: Surface? = null
    private var cameraReady = false
    private var bound = false
    private var released = false
    private var attempt = 0
    private var supported: List<VideoMode> = emptyList()
    private var triedRaise = false
    private var previewWidth = 0
    private var previewHeight = 0
    private var requestedWidth = PreviewSizeChooser.MAX_WIDTH
    private var requestedHeight = PreviewSizeChooser.MAX_HEIGHT

    private val sizeWatch = object : Runnable {
        override fun run() {
            if (released) return
            publishActualSize()
            mainHandler.postDelayed(this, SIZE_WATCH_MS)
        }
    }

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
            rememberSupported()
            if (raiseIfSmaller()) return
            publishActualSize()
            bindSurface()
            watchSize()
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
            if (!cameraReady && modeFor(attempt + 1) != null) {
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
        mainHandler.removeCallbacks(sizeWatch)
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

    /**
     * UVCAndroid's no-arg [UVCParam] opens at 640x480. Ask for 1920x1080 MJPEG
     * first, then the next supported mode if that open fails.
     */
    private fun openCurrentAttempt() {
        if (released) return
        val mode = modeFor(attempt)
        if (mode == null) {
            listener.onFailed("Could not open the USB video device")
            return
        }
        requestedWidth = mode.width
        requestedHeight = mode.height
        Log.i(TAG, "Opening UVC ${mode.width}x${mode.height}@${mode.fps} type=${mode.type} attempt=$attempt")
        try {
            helper.openCamera(UVCParam(toSize(mode), UVCCamera.UVC_QUIRK_FIX_BANDWIDTH))
        } catch (error: Exception) {
            Log.e(TAG, "openCamera", error)
            listener.onFailed(error.message ?: "Could not open the USB video device")
        }
    }

    private fun modeFor(index: Int): VideoMode? {
        val modes = if (supported.isNotEmpty()) {
            PreviewSizeChooser.ranked(supported)
        } else {
            DEFAULT_OPENS
        }
        return modes.getOrNull(index)
    }

    private fun rememberSupported() {
        val reported = (helper.supportedSizeList ?: emptyList()).map { size ->
            VideoMode(
                type = size.type,
                width = size.width,
                height = size.height,
                fps = size.fps,
                fpsOptions = size.fpsList ?: emptyList(),
            )
        }
        if (reported.isNotEmpty()) supported = reported
    }

    /**
     * The device can ignore the requested size and stay at 640x480. Move up to
     * the best listed mode. A failed [CameraHelper.setPreviewSize] destroys the
     * camera inside the library, so a closed camera starts the next mode.
     */
    private fun raiseIfSmaller(): Boolean {
        if (triedRaise) return false
        val best = PreviewSizeChooser.choose(supported) ?: return false
        val actual = currentPreviewSize()
        if (actual != null && actual.first == best.width && actual.second == best.height) return false
        Log.i(TAG, "Raising preview from ${actual?.first ?: 0}x${actual?.second ?: 0} to ${best.width}x${best.height}")
        triedRaise = true
        try {
            helper.previewSize = toSize(best)
        } catch (error: Exception) {
            Log.w(TAG, "setPreviewSize", error)
        }
        if (cameraStillOpen()) return false
        Log.w(TAG, "Preview size change closed the camera")
        cameraReady = false
        bound = false
        attempt += 1
        openCurrentAttempt()
        return true
    }

    private fun publishActualSize() {
        val actual = currentPreviewSize() ?: return
        if (actual.first == previewWidth && actual.second == previewHeight) return
        previewWidth = actual.first
        previewHeight = actual.second
        Log.i(TAG, "UVC frame ${previewWidth}x${previewHeight}")
        mainHandler.post {
            if (!released) listener.onVideoSize(previewWidth, previewHeight)
        }
    }

    private fun currentPreviewSize(): Pair<Int, Int>? {
        val size = try {
            helper.previewSize
        } catch (_: Exception) {
            null
        } ?: return null
        if (size.width <= 0 || size.height <= 0) return null
        return size.width to size.height
    }

    private fun cameraStillOpen(): Boolean {
        return try {
            helper.isCameraOpened
        } catch (_: Exception) {
            false
        }
    }

    private fun watchSize() {
        mainHandler.removeCallbacks(sizeWatch)
        mainHandler.post(sizeWatch)
    }

    private fun toSize(mode: VideoMode): Size {
        val fpsList = ArrayList<Int>(mode.fpsOptions.size.coerceAtLeast(1))
        if (mode.fpsOptions.isEmpty()) fpsList += mode.fps else fpsList += mode.fpsOptions
        return Size(mode.type, mode.width, mode.height, mode.fps, fpsList)
    }

    private fun bindSurface() {
        val target = surface ?: return
        if (bound || released || !cameraReady) return
        bound = true
        helper.addSurface(target, false)
        helper.startPreview()
        val width = if (previewWidth > 0) previewWidth else requestedWidth
        val height = if (previewHeight > 0) previewHeight else requestedHeight
        listener.onPreviewStarted(width, height)
    }

    companion object {
        private const val TAG = "HdmiIn"
        private const val SIZE_WATCH_MS = 1_000L

        /** Used until the device has listed its modes. Highest first, never the library's 640x480 default. */
        private val DEFAULT_OPENS = listOf(
            VideoMode(PreviewSizeChooser.MJPEG_FRAME, 1920, 1080, PreviewSizeChooser.TARGET_FPS),
            VideoMode(PreviewSizeChooser.MJPEG_FRAME, 1280, 720, PreviewSizeChooser.TARGET_FPS),
            VideoMode(PreviewSizeChooser.MJPEG_FRAME, 720, 480, PreviewSizeChooser.TARGET_FPS),
            VideoMode(PreviewSizeChooser.MJPEG_FRAME, 640, 480, PreviewSizeChooser.TARGET_FPS),
        )
    }
}
