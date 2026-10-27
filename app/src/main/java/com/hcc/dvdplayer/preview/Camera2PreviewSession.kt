package com.hcc.dvdplayer.preview

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import java.util.concurrent.Executor
import androidx.core.content.ContextCompat
import com.hcc.dvdplayer.logic.PreviewSizeChooser
import com.hcc.dvdplayer.logic.VideoMode

/**
 * Preview path for boards whose camera HAL already exposes the USB camera as an
 * external Camera2 device. The platform owns the USB interface, so the USB-audio
 * driver is less likely to be kicked off the dongle than with libusb.
 */
class Camera2PreviewSession(
    context: Context,
    private val cameraId: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onAspectKnown(width: Int, height: Int)
        fun onPreviewStarted(width: Int, height: Int)
        fun onDetached()
        fun onFailed(message: String)
    }

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("hdmi-camera2").also { it.start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var released = false
    private var opened = false
    private var pendingSurface: Surface? = null
    private var sessionSurface: Surface? = null

    val previewWidth: Int
    val previewHeight: Int

    init {
        val mode = chooseMode()
        previewWidth = mode?.width ?: 16
        previewHeight = mode?.height ?: 9
    }

    fun start(surface: Surface) {
        if (released) return
        pendingSurface = surface
        if (opened) {
            handler.post { retargetIfReady() }
            return
        }
        opened = true
        listener.onAspectKnown(previewWidth, previewHeight)
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onFailed("Camera permission is required")
            return
        }
        try {
            open(surface)
        } catch (error: SecurityException) {
            listener.onFailed("Camera permission is required")
        } catch (error: Exception) {
            Log.e(TAG, "openCamera", error)
            listener.onFailed(error.message ?: "Could not open the external camera")
        }
    }

    fun release() {
        released = true
        try {
            session?.close()
        } catch (error: Exception) {
            Log.w(TAG, "session close", error)
        }
        try {
            device?.close()
        } catch (error: Exception) {
            Log.w(TAG, "device close", error)
        }
        session = null
        device = null
        thread.quitSafely()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun open(surface: Surface) {
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (released) {
                    camera.close()
                    return
                }
                device = camera
                val target = pendingSurface ?: surface
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                request.addTarget(target)
                request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                sessionSurface = target
                val sessionCallback = object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(captureSession: CameraCaptureSession) {
                            if (released) {
                                captureSession.close()
                                return
                            }
                            session = captureSession
                            try {
                                captureSession.setRepeatingRequest(request.build(), null, handler)
                                emit { listener.onPreviewStarted(previewWidth, previewHeight) }
                            } catch (error: Exception) {
                                Log.e(TAG, "setRepeatingRequest", error)
                                emit { listener.onFailed(error.message ?: "Could not start the camera preview") }
                            }
                        }

                        override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                            emit { listener.onFailed("Could not configure the camera preview") }
                        }
                    }
                createSession(camera, target, sessionCallback)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (device === camera) device = null
                emit { listener.onDetached() }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera2 error $error")
                camera.close()
                if (device === camera) device = null
                emit { listener.onFailed("Camera error $error") }
            }
        }, handler)
    }

    private fun retargetIfReady() {
        val camera = device ?: return
        val target = pendingSurface ?: return
        if (released || target === sessionSurface) return
        try {
            session?.close()
        } catch (error: Exception) {
            Log.w(TAG, "session close", error)
        }
        session = null
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        request.addTarget(target)
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        sessionSurface = target
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                if (released || pendingSurface !== target) {
                    captureSession.close()
                    return
                }
                session = captureSession
                try {
                    captureSession.setRepeatingRequest(request.build(), null, handler)
                } catch (error: Exception) {
                    Log.w(TAG, "retarget", error)
                }
            }

            override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                Log.w(TAG, "retarget configure failed")
            }
        }
        try {
            createSession(camera, target, callback)
        } catch (error: Exception) {
            Log.w(TAG, "retarget", error)
        }
    }

    private fun createSession(
        camera: CameraDevice,
        surface: Surface,
        callback: CameraCaptureSession.StateCallback,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val executor = Executor { command -> handler.post(command) }
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(surface)),
                    executor,
                    callback,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface), callback, handler)
        }
    }

    private fun chooseMode(): VideoMode? {
        val characteristics = try {
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (error: Exception) {
            Log.w(TAG, "characteristics", error)
            return null
        }
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(SurfaceHolder::class.java)
            ?: map.getOutputSizes(SurfaceTexture::class.java)
            ?: return null
        val modes = sizes.map { size -> VideoMode(type = 0, width = size.width, height = size.height, fps = 30) }
        return PreviewSizeChooser.choose(modes, preferMjpeg = false)
    }

    private fun emit(block: () -> Unit) {
        mainHandler.post {
            if (!released) block()
        }
    }

    companion object {
        private const val TAG = "HdmiIn"
    }
}
