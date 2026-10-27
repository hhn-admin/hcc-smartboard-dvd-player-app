package com.hcc.dvdplayer

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
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
import com.hcc.dvdplayer.logic.BlankFrame
import com.hcc.dvdplayer.logic.BlankWatch
import com.hcc.dvdplayer.logic.ManagedSettings
import com.hcc.dvdplayer.logic.PipAspect
import com.hcc.dvdplayer.logic.PipEntry
import com.hcc.dvdplayer.logic.RememberedSource
import com.hcc.dvdplayer.logic.RuntimePermissions
import com.hcc.dvdplayer.preview.Camera2PreviewSession
import com.hcc.dvdplayer.preview.UvcPreviewSession
import com.hcc.dvdplayer.source.AppSettings
import com.hcc.dvdplayer.source.LastSourceStore
import com.hcc.dvdplayer.source.ListedSource
import com.hcc.dvdplayer.source.SnapshotSaver
import com.hcc.dvdplayer.source.SourceCatalog
import com.hcc.dvdplayer.source.UsbEvents
import java.lang.ref.WeakReference
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var appSettings: AppSettings
    private var settings: ManagedSettings = ManagedSettings.parse(
        com.hcc.dvdplayer.logic.RestrictionInput(emptySet()),
        localLock = false,
    )
    private val handler = Handler(Looper.getMainLooper())
    private var uvc: UvcPreviewSession? = null
    private var camera2: Camera2PreviewSession? = null
    private var audio: AudioLoopback? = null
    private var audioStarted = false
    private var recordRequestInFlight = false
    private var pendingSyncDelay = AudioFormatPlanner.UVC_SYNC_DELAY_MS
    private var leaving = false
    private var resolution: String? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var zoomFill = false
    private var audioMessage: String? = null
    private var audioSticky = false
    private var pictureStarted = false
    private var frozen = false
    private var frozenBitmap: Bitmap? = null
    private var snapshotInFlight = false
    private var freezeInFlight = false
    private var mediaReleased = false
    private var pipReceiverRegistered = false
    private val blankWatch = BlankWatch()
    private var lockDownX = 0f
    private var lockDownY = 0f

    private val hideChrome = Runnable {
        binding.backBar.visibility = View.GONE
    }
    private val hideStatus = Runnable {
        if (!audioSticky) binding.status.visibility = View.GONE
    }
    private val hideSnapshotConfirm = Runnable {
        binding.snapshotConfirm.visibility = View.GONE
        binding.snapshotThumb.setImageDrawable(null)
    }
    private val lockHold = Runnable { toggleLocalLock() }
    private val blankPoll = object : Runnable {
        override fun run() {
            sampleBlank()
            handler.postDelayed(this, BLANK_POLL_MS)
        }
    }
    private val meterPoll = object : Runnable {
        override fun run() {
            if (settings.showAudioMeter) {
                binding.audioMeter.setLevel(audio?.level() ?: 0f)
            }
            handler.postDelayed(this, METER_POLL_MS)
        }
    }

    private val pictureGestures = GestureDetector(
        this,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                showChrome()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!settings.lockControls) toggleZoom()
                return true
            }
        },
    )

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

    private val storageRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (leaving) return@registerForActivityResult
        captureSnapshot()
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

    private val restrictionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (leaving || !::binding.isInitialized) return
            settings = appSettings.current()
            applyControlPolicy()
            updatePipParams()
            if (!inPip() && binding.backBar.visibility == View.VISIBLE) showChrome()
        }
    }

    private val pipActionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (mediaReleased || leaving) return
            when (intent?.action) {
                ACTION_PIP_FREEZE -> if (settings.enableFreeze) toggleFreeze()
                ACTION_PIP_MUTE -> toggleMute()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        appSettings = AppSettings(this)
        settings = appSettings.current()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()
        zoomFill = settings.zoomToFillDefault
        binding.videoFrame.setCropToFill(zoomFill)
        binding.zoomButton.setText(if (zoomFill) R.string.fit_screen else R.string.zoom_to_fill)
        binding.backButton.setOnClickListener { onUserLeavePlayback() }
        binding.zoomButton.setOnClickListener { toggleZoom() }
        binding.freezeButton.setOnClickListener { toggleFreeze() }
        binding.snapshotButton.setOnClickListener { onSnapshotClicked() }
        binding.pipButton.setOnClickListener { enterPipFromButton() }
        binding.frozenBanner.setOnClickListener { if (frozen) clearFreeze() }
        binding.playerRoot.setOnClickListener { showChrome() }
        val pictureTouch = View.OnTouchListener { _, event ->
            trackLockHold(event)
            pictureGestures.onTouchEvent(event)
            true
        }
        binding.videoFrame.setOnTouchListener(pictureTouch)
        binding.preview.setOnTouchListener(pictureTouch)
        binding.noSignal.setOnTouchListener(pictureTouch)
        binding.status.setOnLongClickListener {
            if (!settings.lockControls) toggleDiagnostics()
            true
        }
        binding.diagnosticsClose.setOnClickListener { hideDiagnostics() }
        binding.diagnosticsBody.setOnLongClickListener {
            hideDiagnostics()
            true
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                onUserLeavePlayback()
            }
        })
        applyControlPolicy()
        UsbEvents.register(this, usbReceiver)
        appSettings.registerRestrictionsListener(this, restrictionsReceiver)
        registerPipActions()
        activePlayer = WeakReference(this)
        notePipUnavailable()
        updatePipParams()
        showChrome()
        handler.post(meterPoll)
        when (intent.getStringExtra(EXTRA_KIND)) {
            KIND_USB -> startUsb()
            KIND_CAMERA2 -> startCamera2()
            else -> finishWithNotice(getString(R.string.open_failed))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep the original playback extras. Launcher and USB attach reorder this
        // activity to the front, which expands picture-in-picture. Replacing the
        // intent would drop the source and could open a second capture.
        updatePipParams()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.R) {
            enterPipIfAutomatic()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!::binding.isInitialized || mediaReleased) return
        if (isInPictureInPictureMode) hideChromeForPip() else restoreFromPip()
    }

    override fun onStart() {
        super.onStart()
        if (mediaReleased && !isFinishing) finish()
    }

    override fun onStop() {
        // Paused-but-visible (picture-in-picture) does not stop. Closing the
        // floating window, or leaving without one, does.
        if (!inPip()) releaseMedia()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            onUserLeavePlayback()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        leaving = true
        handler.removeCallbacksAndMessages(null)
        releaseMedia()
        bindingOrNull()?.freezeFrame?.setImageDrawable(null)
        frozenBitmap?.recycle()
        frozenBitmap = null
        if (activePlayer?.get() === this) activePlayer = null
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(restrictionsReceiver)
        } catch (_: Exception) {
        }
        if (pipReceiverRegistered) {
            try {
                unregisterReceiver(pipActionsReceiver)
            } catch (_: Exception) {
            }
            pipReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun bindingOrNull(): ActivityPlayerBinding? = if (::binding.isInitialized) binding else null

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
                onPictureStarted(width, height, AudioFormatPlanner.UVC_SYNC_DELAY_MS)
            }

            override fun onVideoSize(width: Int, height: Int) {
                if (leaving) return
                applyVideoSize(width, height)
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
                applyVideoSize(width, height)
            }

            override fun onPreviewStarted(width: Int, height: Int) {
                onPictureStarted(width, height, AudioFormatPlanner.CAMERA2_SYNC_DELAY_MS)
            }

            override fun onDetached() {
                finishWithNotice(getString(R.string.device_unplugged))
            }

            override fun onFailed(message: String) {
                finishWithNotice(message.ifBlank { getString(R.string.open_failed) })
            }
        })
        camera2 = session
        applyVideoSize(session.previewWidth, session.previewHeight)
        binding.preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (width > 0 && height > 0) session.start(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
        setStatus(getString(R.string.opening_camera), sticky = true)
    }

    private fun onPictureStarted(width: Int, height: Int, syncDelayMs: Int) {
        if (leaving) return
        applyVideoSize(width, height)
        audioSticky = false
        renderStatus()
        scheduleStatusHide()
        rememberPlayingSource()
        if (!pictureStarted) {
            pictureStarted = true
            blankWatch.onOpened(SystemClock.elapsedRealtime())
            handler.removeCallbacks(blankPoll)
            handler.post(blankPoll)
        }
        startAudio(syncDelayMs)
        updatePipParams()
    }

    private fun rememberPlayingSource() {
        val remembered = when (intent.getStringExtra(EXTRA_KIND)) {
            KIND_USB -> {
                val device = SourceCatalog(this).findUsbDevice(intentUsbName() ?: return) ?: return
                RememberedSource(
                    kind = ListedSource.Kind.USB_UVC,
                    vendorId = device.vendorId,
                    productId = device.productId,
                )
            }
            KIND_CAMERA2 -> {
                val cameraId = intent.getStringExtra(EXTRA_CAMERA_ID) ?: return
                RememberedSource(
                    kind = ListedSource.Kind.CAMERA2_EXTERNAL,
                    cameraId = cameraId,
                )
            }
            else -> return
        }
        LastSourceStore(this).save(remembered)
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
            exclusionPolicy = settings.exclusionPolicy,
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
            if (!inPip()) binding.status.visibility = View.VISIBLE
        } else if (!inPip()) {
            scheduleStatusHide()
        }
    }

    private fun setStatus(message: String, sticky: Boolean) {
        audioMessage = message
        audioSticky = sticky
        renderStatus()
    }

    private fun applyVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val changed = width != videoWidth || height != videoHeight
        videoWidth = width
        videoHeight = height
        binding.videoFrame.setAspect(width, height)
        resolution = getString(R.string.resolution_fmt, width, height)
        if (changed && !audioSticky && !inPip()) {
            renderStatus()
            scheduleStatusHide()
        }
        updatePipParams()
    }

    private fun toggleZoom() {
        if (settings.lockControls) return
        zoomFill = !zoomFill
        binding.videoFrame.setCropToFill(zoomFill)
        binding.zoomButton.setText(if (zoomFill) R.string.fit_screen else R.string.zoom_to_fill)
        if (!audioSticky) {
            val fit = getString(if (zoomFill) R.string.zoom_on else R.string.zoom_off)
            binding.status.text = listOfNotNull(resolution, fit).joinToString(" · ")
            binding.status.visibility = View.VISIBLE
            scheduleStatusHide()
        }
        showChrome()
    }

    private fun pictureDescription(): String {
        if (videoWidth <= 0 || videoHeight <= 0) return "not started"
        val fit = if (zoomFill) "zoom to fill" else "fit"
        val hold = if (frozen) ", frozen" else ""
        return "${videoWidth}x${videoHeight}, $fit$hold"
    }

    private fun renderStatus() {
        val text = listOfNotNull(resolution, audioMessage).filter { it.isNotBlank() }.joinToString(" · ")
        binding.status.text = text
        binding.status.visibility = if (text.isEmpty() || inPip()) View.GONE else View.VISIBLE
    }

    private fun scheduleStatusHide() {
        handler.removeCallbacks(hideStatus)
        handler.postDelayed(hideStatus, STATUS_HIDE_MS)
    }

    private fun toggleDiagnostics() {
        if (settings.lockControls) return
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
                picture = pictureDescription(),
                policy = settings.exclusionPolicy,
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
        if (inPip()) {
            binding.backBar.visibility = View.GONE
            return
        }
        binding.backBar.visibility = View.VISIBLE
        handler.removeCallbacks(hideChrome)
        handler.postDelayed(hideChrome, settings.controlsAutohideSeconds * 1_000L)
    }

    private fun applyControlPolicy() {
        if (inPip()) {
            hideChromeForPip()
            if (!settings.enableFreeze && frozen) clearFreeze()
            return
        }
        val locked = settings.lockControls
        binding.backButton.visibility = if (locked) View.GONE else View.VISIBLE
        binding.zoomButton.visibility = if (locked) View.GONE else View.VISIBLE
        binding.snapshotButton.visibility = if (settings.enableSnapshot) View.VISIBLE else View.GONE
        binding.freezeButton.visibility = if (settings.enableFreeze) View.VISIBLE else View.GONE
        binding.pipButton.visibility = if (PipEntry.buttonVisible(settings.enablePip, pipFeature())) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.audioMeter.visibility = if (settings.showAudioMeter) View.VISIBLE else View.GONE
        if (locked) hideDiagnostics()
        if (!settings.enableFreeze && frozen) clearFreeze()
    }

    private fun onUserLeavePlayback() {
        if (settings.lockControls) {
            if (binding.diagnostics.visibility == View.VISIBLE) hideDiagnostics()
            showChrome()
            return
        }
        if (binding.diagnostics.visibility == View.VISIBLE) {
            hideDiagnostics()
        } else {
            finishWithNotice(null)
        }
    }

    private fun trackLockHold(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lockDownX = event.x
                lockDownY = event.y
                handler.removeCallbacks(lockHold)
                handler.postDelayed(lockHold, LOCK_HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (abs(event.x - lockDownX) > slop || abs(event.y - lockDownY) > slop) {
                    handler.removeCallbacks(lockHold)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(lockHold)
        }
    }

    private fun toggleLocalLock() {
        settings = appSettings.current()
        if (settings.lockManaged) {
            if (settings.lockControls) {
                Toast.makeText(this, R.string.controls_locked_by_admin, Toast.LENGTH_SHORT).show()
            }
            applyControlPolicy()
            return
        }
        val locked = !settings.lockControls
        appSettings.setLocalLock(locked)
        settings = appSettings.current()
        applyControlPolicy()
        Toast.makeText(
            this,
            if (locked) R.string.controls_locked else R.string.controls_unlocked,
            Toast.LENGTH_SHORT,
        ).show()
        showChrome()
    }

    private fun toggleFreeze() {
        if (!settings.enableFreeze || freezeInFlight) return
        if (frozen) clearFreeze() else freezePicture()
    }

    private fun freezePicture() {
        val view = binding.preview
        if (view.width < 2 || view.height < 2 || !view.holder.surface.isValid) {
            Toast.makeText(this, R.string.freeze_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        freezeInFlight = true
        try {
            PixelCopy.request(view, bitmap, { result ->
                freezeInFlight = false
                if (leaving) {
                    bitmap.recycle()
                    return@request
                }
                if (result != PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    Toast.makeText(this, R.string.freeze_failed, Toast.LENGTH_SHORT).show()
                    return@request
                }
                frozenBitmap?.recycle()
                frozenBitmap = bitmap
                frozen = true
                binding.freezeFrame.setImageBitmap(bitmap)
                binding.freezeFrame.visibility = View.VISIBLE
                binding.frozenBanner.visibility = View.VISIBLE
                binding.noSignal.visibility = View.GONE
                binding.freezeButton.setText(R.string.live)
                updatePipParams()
                showChrome()
            }, handler)
        } catch (_: Exception) {
            freezeInFlight = false
            bitmap.recycle()
            Toast.makeText(this, R.string.freeze_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearFreeze() {
        frozen = false
        freezeInFlight = false
        binding.freezeFrame.setImageDrawable(null)
        binding.freezeFrame.visibility = View.GONE
        binding.frozenBanner.visibility = View.GONE
        frozenBitmap?.recycle()
        frozenBitmap = null
        binding.freezeButton.setText(R.string.freeze)
        updatePipParams()
        renderNoSignal()
    }

    private fun onSnapshotClicked() {
        if (!settings.enableSnapshot || snapshotInFlight) return
        if (needsLegacyStoragePermission()) {
            storageRequest.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        captureSnapshot()
    }

    private fun needsLegacyStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
    }

    private fun captureSnapshot() {
        if (snapshotInFlight || leaving) return
        val held = frozenBitmap
        if (held != null && !held.isRecycled) {
            saveSnapshot(held, recycleAfter = false)
            return
        }
        val view = binding.preview
        if (view.width < 2 || view.height < 2 || !view.holder.surface.isValid) {
            Toast.makeText(this, R.string.snapshot_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        snapshotInFlight = true
        try {
            PixelCopy.request(view, bitmap, { result ->
                snapshotInFlight = false
                if (leaving) {
                    bitmap.recycle()
                    return@request
                }
                if (result == PixelCopy.SUCCESS) {
                    saveSnapshot(bitmap, recycleAfter = true)
                } else {
                    bitmap.recycle()
                    Toast.makeText(this, R.string.snapshot_failed, Toast.LENGTH_SHORT).show()
                }
            }, handler)
        } catch (_: Exception) {
            snapshotInFlight = false
            bitmap.recycle()
            Toast.makeText(this, R.string.snapshot_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveSnapshot(bitmap: Bitmap, recycleAfter: Boolean) {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        val saved = try {
            SnapshotSaver(this).save(bitmap, legacyStorageGranted = granted)
        } catch (_: Exception) {
            null
        }
        if (saved == null) {
            Toast.makeText(this, R.string.snapshot_failed, Toast.LENGTH_SHORT).show()
        } else {
            showSnapshotSaved(bitmap, saved.publicPictures)
        }
        if (recycleAfter && !bitmap.isRecycled) bitmap.recycle()
    }

    private fun showSnapshotSaved(bitmap: Bitmap, publicPictures: Boolean) {
        val message = getString(if (publicPictures) R.string.snapshot_saved else R.string.snapshot_saved_private)
        binding.snapshotMessage.text = message
        if (!bitmap.isRecycled) {
            val width = 192
            val height = (width * bitmap.height / bitmap.width.coerceAtLeast(1)).coerceAtLeast(1)
            binding.snapshotThumb.setImageBitmap(Bitmap.createScaledBitmap(bitmap, width, height, true))
        }
        binding.snapshotConfirm.visibility = View.VISIBLE
        handler.removeCallbacks(hideSnapshotConfirm)
        handler.postDelayed(hideSnapshotConfirm, SNAPSHOT_CONFIRM_MS)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        showChrome()
    }

    private fun sampleBlank() {
        if (leaving || !pictureStarted) return
        if (frozen) {
            binding.noSignal.visibility = View.GONE
            return
        }
        val view = binding.preview
        if (view.width < 2 || view.height < 2 || !view.holder.surface.isValid) return
        val bitmap = Bitmap.createBitmap(BLANK_SAMPLE_WIDTH, BLANK_SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(view, bitmap, { result ->
                val blank = if (result == PixelCopy.SUCCESS) {
                    val pixels = IntArray(BLANK_SAMPLE_WIDTH * BLANK_SAMPLE_HEIGHT)
                    bitmap.getPixels(
                        pixels,
                        0,
                        BLANK_SAMPLE_WIDTH,
                        0,
                        0,
                        BLANK_SAMPLE_WIDTH,
                        BLANK_SAMPLE_HEIGHT,
                    )
                    BlankFrame.isMostlyBlack(pixels)
                } else {
                    null
                }
                bitmap.recycle()
                if (leaving) return@request
                blankWatch.onSample(SystemClock.elapsedRealtime(), blank, frozen)
                renderNoSignal()
            }, handler)
        } catch (_: Exception) {
            bitmap.recycle()
        }
    }

    private fun renderNoSignal() {
        binding.noSignal.visibility = if (blankWatch.visible && !frozen) View.VISIBLE else View.GONE
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

    private fun keepsPlayback(): Boolean {
        return !mediaReleased && !isFinishing && !leaving && (pictureStarted || uvc != null || camera2 != null)
    }

    private fun releaseMedia() {
        if (mediaReleased) return
        mediaReleased = true
        handler.removeCallbacks(blankPoll)
        handler.removeCallbacks(meterPoll)
        val currentAudio = audio
        audio = null
        currentAudio?.stop()
        uvc?.release()
        uvc = null
        camera2?.release()
        camera2 = null
        blankWatch.onClosed()
    }

    private fun inPip(): Boolean = isInPictureInPictureMode

    private fun pipFeature(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun shouldAutoEnter(): Boolean {
        return PipEntry.autoEnter(
            enablePip = settings.enablePip,
            autoOnLeave = settings.autoPipOnLeave,
            featurePresent = pipFeature(),
            pictureStarted = pictureStarted,
            finishing = leaving || isFinishing || mediaReleased,
            inPip = inPip(),
        )
    }

    private fun registerPipActions() {
        if (pipReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_PIP_FREEZE)
            addAction(ACTION_PIP_MUTE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipActionsReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipActionsReceiver, filter)
        }
        pipReceiverRegistered = true
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !pipFeature() || mediaReleased) return
        if (!::binding.isInitialized) return
        try {
            setPictureInPictureParams(buildPipParams())
        } catch (error: IllegalStateException) {
            Log.w(TAG, "setPictureInPictureParams", error)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "setPictureInPictureParams", error)
        }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val (width, height) = PipAspect.rational(
            if (videoWidth > 0) videoWidth else 16,
            if (videoHeight > 0) videoHeight else 9,
        )
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(width, height))
            .setActions(pipRemoteActions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(shouldAutoEnter())
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun pipRemoteActions(): List<RemoteAction> {
        val actions = ArrayList<RemoteAction>(2)
        if (settings.enableFreeze) {
            actions += remoteAction(
                requestCode = 1,
                action = ACTION_PIP_FREEZE,
                icon = if (frozen) R.drawable.ic_pip_live else R.drawable.ic_pip_freeze,
                label = getString(if (frozen) R.string.live else R.string.freeze),
            )
        }
        val muted = audio?.isMuted() == true
        actions += remoteAction(
            requestCode = 2,
            action = ACTION_PIP_MUTE,
            icon = if (muted) R.drawable.ic_pip_sound else R.drawable.ic_pip_mute,
            label = getString(if (muted) R.string.pip_unmute else R.string.pip_mute),
        )
        return actions
    }

    private fun remoteAction(requestCode: Int, action: String, icon: Int, label: String): RemoteAction {
        val pending = PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(Icon.createWithResource(this, icon), label, label, pending)
    }

    private fun enterPipFromButton() {
        if (!pipFeature()) {
            tellPipUnavailable()
            return
        }
        enterPipNow(reportFailure = true)
    }

    private fun enterPipIfAutomatic() {
        if (!shouldAutoEnter()) return
        enterPipNow(reportFailure = false)
    }

    private fun enterPipNow(reportFailure: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (reportFailure) tellPipUnavailable()
            return
        }
        try {
            val params = buildPipParams()
            setPictureInPictureParams(params)
            if (!enterPictureInPictureMode(params)) {
                Log.i(TAG, "enterPictureInPictureMode returned false")
                if (reportFailure) tellPipUnavailable()
            }
        } catch (error: IllegalStateException) {
            Log.w(TAG, "enterPictureInPictureMode", error)
            if (reportFailure) tellPipUnavailable()
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "enterPictureInPictureMode", error)
            if (reportFailure) tellPipUnavailable()
        }
    }

    private fun notePipUnavailable() {
        if (!settings.enablePip || pipFeature() || notedPipUnavailable) return
        notedPipUnavailable = true
        tellPipUnavailable()
    }

    private fun tellPipUnavailable() {
        Log.i(TAG, getString(R.string.pip_unavailable))
        if (isFinishing) return
        Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_SHORT).show()
    }

    private fun toggleMute() {
        audio?.setMuted(audio?.isMuted() != true)
        updatePipParams()
    }

    private fun hideChromeForPip() {
        binding.backBar.visibility = View.GONE
        binding.status.visibility = View.GONE
        binding.audioMeter.visibility = View.GONE
        binding.frozenBanner.visibility = View.GONE
        binding.snapshotConfirm.visibility = View.GONE
        binding.diagnostics.visibility = View.GONE
        binding.noSignal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        handler.removeCallbacks(hideChrome)
    }

    private fun restoreFromPip() {
        binding.noSignal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        applyControlPolicy()
        renderNoSignal()
        if (audioSticky) {
            binding.status.visibility = View.VISIBLE
        } else {
            renderStatus()
        }
        if (frozen) binding.frozenBanner.visibility = View.VISIBLE
        showChrome()
        enterImmersive()
        updatePipParams()
    }

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

        private const val TAG = "DvdPlayer"
        private const val ACTION_PIP_FREEZE = "com.hcc.dvdplayer.action.PIP_FREEZE"
        private const val ACTION_PIP_MUTE = "com.hcc.dvdplayer.action.PIP_MUTE"
        private const val STATUS_HIDE_MS = 3_000L

        private var activePlayer: WeakReference<PlayerActivity>? = null
        private var notedPipUnavailable = false

        /**
         * Launcher and USB-attach intents land on [PickerActivity]. When this
         * player is already showing a picture, bring it forward instead of
         * stacking another copy. Resuming it leaves picture-in-picture.
         */
        fun expandExisting(context: Context): Boolean {
            val player = activePlayer?.get() ?: return false
            if (!player.keepsPlayback()) return false
            context.startActivity(
                Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            return true
        }
        private const val LOCK_HOLD_MS = 3_000L
        private const val BLANK_POLL_MS = 2_000L
        private const val METER_POLL_MS = 100L
        private const val SNAPSHOT_CONFIRM_MS = 2_500L
        private const val BLANK_SAMPLE_WIDTH = 32
        private const val BLANK_SAMPLE_HEIGHT = 18
    }
}
