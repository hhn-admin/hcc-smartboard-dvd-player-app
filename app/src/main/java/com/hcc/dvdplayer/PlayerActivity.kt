package com.hcc.dvdplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hcc.dvdplayer.audio.AudioLoopback
import com.hcc.dvdplayer.audio.PlaybackDiagnostics
import com.hcc.dvdplayer.databinding.ActivityPlayerBinding
import com.hcc.dvdplayer.logic.AudioFormatPlanner
import com.hcc.dvdplayer.logic.RuntimePermissions
import com.hcc.dvdplayer.preview.Camera2PreviewSession
import com.hcc.dvdplayer.preview.UvcPreviewSession
import com.hcc.dvdplayer.source.SourceCatalog
import com.hcc.dvdplayer.source.UsbEvents

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private val handler = Handler(Looper.getMainLooper())
    private var uvc: UvcPreviewSession? = null
    private var camera2: Camera2PreviewSession? = null
    private var audio: AudioLoopback? = null
    private var audioStarted = false
    private var recordRequestInFlight = false
    private var pendingSyncDelay = AudioFormatPlanner.UVC_SYNC_DELAY_MS
    private var leaving = false
    private var resolution: String? = null
    private var audioMessage: String? = null
    private var audioSticky = false

    private val hideChrome = Runnable {
        binding.backBar.visibility = View.GONE
    }
    private val hideStatus = Runnable {
        if (!audioSticky) binding.status.visibility = View.GONE
    }

    private val recordAudioRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        recordRequestInFlight = false
        if (leaving) return@registerForActivityResult
        if (granted) {
            audioStarted = false
            startAudio(pendingSyncDelay)
        } else {
            showAudio(getString(R.string.audio_permission_denied), sticky = true)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val name = UsbEvents.deviceName(intent) ?: return
            val playing = intentUsbName()
            if (playing != null && playing == name) {
                finishWithNotice(getString(R.string.device_unplugged))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()
        binding.backButton.setOnClickListener { finishWithNotice(null) }
        binding.playerRoot.setOnClickListener { showChrome() }
        binding.preview.setOnClickListener { showChrome() }
        binding.status.setOnLongClickListener {
            toggleDiagnostics()
            true
        }
        binding.preview.setOnLongClickListener {
            toggleDiagnostics()
            true
        }
        binding.diagnosticsClose.setOnClickListener { hideDiagnostics() }
        binding.diagnosticsBody.setOnLongClickListener {
            hideDiagnostics()
            true
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.diagnostics.visibility == View.VISIBLE) {
                    hideDiagnostics()
                } else {
                    finishWithNotice(null)
                }
            }
        })
        UsbEvents.register(this, usbReceiver)
        showChrome()
        when (intent.getStringExtra(EXTRA_KIND)) {
            KIND_USB -> startUsb()
            KIND_CAMERA2 -> startCamera2()
            else -> finishWithNotice(getString(R.string.open_failed))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (binding.diagnostics.visibility == View.VISIBLE) {
                hideDiagnostics()
            } else {
                finishWithNotice(null)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        audio?.stop()
        audio = null
        uvc?.release()
        uvc = null
        camera2?.release()
        camera2 = null
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun startUsb() {
        val deviceName = intentUsbName()
        if (deviceName == null || SourceCatalog(this).findUsbDevice(deviceName) == null) {
            finishWithNotice(getString(R.string.device_unplugged))
            return
        }
        binding.preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                uvc?.onSurfaceReady(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                uvc?.onSurfaceDestroyed(holder.surface)
            }
        })
        setStatus(getString(R.string.opening_usb), sticky = true)
        uvc = UvcPreviewSession(deviceName, object : UvcPreviewSession.Listener {
            override fun onPreviewStarted(width: Int, height: Int) {
                if (leaving) return
                binding.videoFrame.setAspect(width, height)
                resolution = getString(R.string.resolution_fmt, width, height)
                audioSticky = false
                renderStatus()
                scheduleStatusHide()
                startAudio(AudioFormatPlanner.UVC_SYNC_DELAY_MS)
            }

            override fun onUsbPermissionDenied() {
                finishWithNotice(getString(R.string.usb_permission_denied))
            }

            override fun onDetached() {
                finishWithNotice(getString(R.string.device_unplugged))
            }

            override fun onFailed(message: String) {
                finishWithNotice(message.ifBlank { getString(R.string.open_failed) })
            }
        }).also { it.start() }
    }

    private fun startCamera2() {
        val cameraId = intent.getStringExtra(EXTRA_CAMERA_ID)
        if (cameraId.isNullOrBlank()) {
            finishWithNotice(getString(R.string.open_failed))
            return
        }
        if (ContextCompat.checkSelfPermission(this, RuntimePermissions.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finishWithNotice(getString(R.string.camera_permission_required))
            return
        }
        val session = Camera2PreviewSession(this, cameraId, object : Camera2PreviewSession.Listener {
            override fun onAspectKnown(width: Int, height: Int) {
                binding.videoFrame.setAspect(width, height)
            }

            override fun onPreviewStarted(width: Int, height: Int) {
                if (leaving) return
                binding.videoFrame.setAspect(width, height)
                resolution = getString(R.string.resolution_fmt, width, height)
                audioSticky = false
                renderStatus()
                scheduleStatusHide()
                startAudio(AudioFormatPlanner.CAMERA2_SYNC_DELAY_MS)
            }

            override fun onDetached() {
                finishWithNotice(getString(R.string.device_unplugged))
            }

            override fun onFailed(message: String) {
                finishWithNotice(message.ifBlank { getString(R.string.open_failed) })
            }
        })
        camera2 = session
        binding.videoFrame.setAspect(session.previewWidth, session.previewHeight)
        binding.preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (width > 0 && height > 0) session.start(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
        setStatus(getString(R.string.opening_camera), sticky = true)
    }

    private fun startAudio(syncDelayMs: Int) {
        if (audioStarted || leaving || recordRequestInFlight) return
        pendingSyncDelay = syncDelayMs
        val usb = intent.getStringExtra(EXTRA_KIND) == KIND_USB
        if (!usb && !hasRecordAudio()) {
            requestRecordAudio()
            return
        }
        audioStarted = true
        val loopback = AudioLoopback(
            context = this,
            productHint = intent.getStringExtra(EXTRA_AUDIO_HINT),
            usbDeviceName = intentUsbName(),
            syncDelayMs = syncDelayMs,
        ) { status ->
            if (leaving) return@AudioLoopback
            when (status) {
                AudioLoopback.Status.STARTING -> showAudio(getString(R.string.audio_starting), sticky = false)
                AudioLoopback.Status.PLAYING -> showAudio(getString(R.string.audio_playing), sticky = false)
                AudioLoopback.Status.NO_USB_INPUT -> showAudio(getString(R.string.audio_missing), sticky = true)
                AudioLoopback.Status.NEEDS_RECORD_AUDIO,
                AudioLoopback.Status.PERMISSION_DENIED -> requestRecordAudio()
                AudioLoopback.Status.FAILED -> showAudio(getString(R.string.audio_failed), sticky = true)
                AudioLoopback.Status.STOPPED -> Unit
            }
        }
        audio = loopback
        loopback.start()
    }

    private fun requestRecordAudio() {
        if (leaving || recordRequestInFlight) return
        if (hasRecordAudio()) {
            showAudio(getString(R.string.audio_failed), sticky = true)
            return
        }
        recordRequestInFlight = true
        audioStarted = false
        val current = audio
        audio = null
        current?.stop()
        showAudio(getString(R.string.audio_need_microphone), sticky = true)
        recordAudioRequest.launch(RuntimePermissions.RECORD_AUDIO)
    }

    private fun hasRecordAudio(): Boolean {
        return ContextCompat.checkSelfPermission(this, RuntimePermissions.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun showAudio(message: String, sticky: Boolean) {
        audioMessage = message
        audioSticky = sticky
        renderStatus()
        if (sticky) {
            handler.removeCallbacks(hideStatus)
            binding.status.visibility = View.VISIBLE
        } else {
            scheduleStatusHide()
        }
    }

    private fun setStatus(message: String, sticky: Boolean) {
        audioMessage = message
        audioSticky = sticky
        renderStatus()
    }

    private fun renderStatus() {
        val text = listOfNotNull(resolution, audioMessage).filter { it.isNotBlank() }.joinToString(" · ")
        binding.status.text = text
        binding.status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun scheduleStatusHide() {
        handler.removeCallbacks(hideStatus)
        handler.postDelayed(hideStatus, STATUS_HIDE_MS)
    }

    private fun toggleDiagnostics() {
        if (binding.diagnostics.visibility == View.VISIBLE) {
            hideDiagnostics()
            return
        }
        val text = try {
            PlaybackDiagnostics(this).render(
                versionName = BuildConfig.VERSION_NAME,
                playingDeviceName = intentUsbName(),
                productHint = intent.getStringExtra(EXTRA_AUDIO_HINT),
                directUsb = audio?.directUsbNote() ?: "not started",
            )
        } catch (error: Exception) {
            "Diagnostics failed: ${error.message ?: error.javaClass.simpleName}"
        }
        binding.diagnosticsBody.text = text
        binding.diagnostics.visibility = View.VISIBLE
        binding.status.visibility = View.VISIBLE
        binding.diagnosticsClose.requestFocus()
    }

    private fun hideDiagnostics() {
        binding.diagnostics.visibility = View.GONE
    }

    private fun showChrome() {
        binding.backBar.visibility = View.VISIBLE
        handler.removeCallbacks(hideChrome)
        handler.postDelayed(hideChrome, CHROME_HIDE_MS)
    }

    private fun finishWithNotice(message: String?) {
        if (leaving) return
        leaving = true
        if (!message.isNullOrBlank()) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_NOTICE, message))
        }
        finish()
    }

    private fun intentUsbName(): String? = intent.getStringExtra(EXTRA_USB_DEVICE)

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_USB_DEVICE = "usb_device"
        const val EXTRA_CAMERA_ID = "camera_id"
        const val EXTRA_AUDIO_HINT = "audio_hint"
        const val EXTRA_NOTICE = "notice"
        const val KIND_USB = "usb"
        const val KIND_CAMERA2 = "camera2"

        private const val CHROME_HIDE_MS = 4_000L
        private const val STATUS_HIDE_MS = 3_000L
    }
}
