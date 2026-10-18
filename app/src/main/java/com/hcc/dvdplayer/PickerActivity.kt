package com.hcc.dvdplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AnimationUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import kotlin.math.max
import com.hcc.dvdplayer.databinding.ActivityPickerBinding
import com.hcc.dvdplayer.logic.PickerPrompt
import com.hcc.dvdplayer.source.ListedSource
import com.hcc.dvdplayer.source.SourceCatalog
import com.hcc.dvdplayer.source.UsbEvents
import com.hcc.dvdplayer.ui.SourceAdapter

class PickerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPickerBinding
    private lateinit var catalog: SourceCatalog
    private val adapter = SourceAdapter(::onSourceClicked)
    private val handler = Handler(Looper.getMainLooper())
    private var signature = ""
    private var pending: ListedSource? = null
    private var permissionDenied = false
    private var returnedFromPlayer = false
    private var openInProgress = false
    private var inRefresh = false

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val source = pending
        pending = null
        val granted = missingPermissions().isEmpty()
        permissionDenied = !granted
        renderPermission()
        if (granted) {
            returnedFromPlayer = false
            if (source != null) {
                open(source)
            } else {
                refresh()
            }
        }
    }

    private val player = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        openInProgress = false
        returnedFromPlayer = true
        val notice = result.data?.getStringExtra(PlayerActivity.EXTRA_NOTICE)
        if (notice.isNullOrBlank()) {
            binding.notice.visibility = View.GONE
        } else {
            binding.notice.text = notice
            binding.notice.visibility = View.VISIBLE
        }
        refresh()
    }

    private val usbReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            refresh()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        catalog = SourceCatalog(this)
        binding.sources.layoutManager = LinearLayoutManager(this)
        binding.sources.adapter = adapter
        binding.permissionButton.setOnClickListener { requestAccess() }
        keepEmptyPromptAboveSystemBars()
        UsbEvents.register(this, usbReceiver)
    }

    override fun onStart() {
        super.onStart()
        refresh()
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, POLL_MS)
    }

    override fun onStop() {
        handler.removeCallbacks(poll)
        binding.emptyArrow.clearAnimation()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    /**
     * The prompt sits in the bottom-left corner, aimed at the bezel ports.
     * The navigation or task bar is also along the bottom, so the block keeps
     * a gap under the arrow and grows that gap when the bars overlay the window.
     */
    private fun keepEmptyPromptAboveSystemBars() {
        val gap = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.emptyState) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val bottom = max(gap, bars.bottom + gap / 2)
            if (view.paddingBottom != bottom) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom)
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.emptyState)
    }

    private fun refresh() {
        if (inRefresh) return
        inRefresh = true
        try {
            val sources = catalog.list()
            val nextSignature = sources.joinToString("\n") { it.signature() }
            val pointed = PickerPrompt.pointedSource(sources)
            if (nextSignature != signature) {
                signature = nextSignature
                adapter.submit(sources, pointed)
            }
            val empty = sources.isEmpty()
            binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
            binding.sources.visibility = if (empty) View.GONE else View.VISIBLE
            val host = catalog.usbHostAvailable()
            binding.empty.setText(
                if (host) R.string.plug_in_dvd_capture_card else R.string.empty_no_usb_host,
            )
            binding.emptyArrow.visibility = if (host) View.VISIBLE else View.GONE
            binding.emptyPlug.visibility = if (host) View.VISIBLE else View.GONE
            if (empty && host) {
                if (binding.emptyArrow.animation == null) {
                    binding.emptyArrow.startAnimation(
                        AnimationUtils.loadAnimation(this, R.anim.arrow_nudge_down),
                    )
                }
            } else {
                binding.emptyArrow.clearAnimation()
            }
            val tapUsb = sources.isEmpty() || sources.any { it.kind == ListedSource.Kind.USB_UVC }
            binding.stepTapLabel.setText(if (tapUsb) R.string.step_tap else R.string.step_tap_only)
            binding.stepPlug.isActivated = empty
            binding.stepTap.isActivated = !empty
            renderPermission()
            if (PickerPrompt.shouldAutoStart(
                    sources = sources,
                    permissionsGranted = missingPermissions().isEmpty(),
                    returnedFromPlayer = returnedFromPlayer,
                    openInProgress = openInProgress,
                )
            ) {
                open(sources.first())
            }
            if (sources.size != 1) returnedFromPlayer = false
        } finally {
            inRefresh = false
        }
    }

    private fun onSourceClicked(source: ListedSource) {
        binding.notice.visibility = View.GONE
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            returnedFromPlayer = false
            open(source)
        } else {
            pending = source
            permissionRequest.launch(missing)
        }
    }

    private fun open(source: ListedSource) {
        if (openInProgress) return
        openInProgress = true
        if (source.kind == ListedSource.Kind.CAMERA2_EXTERNAL && !has(Manifest.permission.CAMERA)) {
            openInProgress = false
            binding.notice.setText(R.string.camera_permission_required)
            binding.notice.visibility = View.VISIBLE
            renderPermission()
            return
        }
        val intent = Intent(this, PlayerActivity::class.java)
        when (source.kind) {
            ListedSource.Kind.USB_UVC -> {
                val name = source.usbDeviceName ?: run {
                    openInProgress = false
                    return
                }
                if (catalog.findUsbDevice(name) == null) {
                    openInProgress = false
                    binding.notice.setText(R.string.device_unplugged)
                    binding.notice.visibility = View.VISIBLE
                    if (!inRefresh) refresh()
                    return
                }
                intent.putExtra(PlayerActivity.EXTRA_KIND, PlayerActivity.KIND_USB)
                intent.putExtra(PlayerActivity.EXTRA_USB_DEVICE, name)
            }
            ListedSource.Kind.CAMERA2_EXTERNAL -> {
                intent.putExtra(PlayerActivity.EXTRA_KIND, PlayerActivity.KIND_CAMERA2)
                intent.putExtra(PlayerActivity.EXTRA_CAMERA_ID, source.cameraId)
            }
        }
        intent.putExtra(PlayerActivity.EXTRA_AUDIO_HINT, source.audioHint)
        player.launch(intent)
    }

    private fun requestAccess() {
        val missing = missingPermissions()
        val permanentlyDenied = permissionDenied && missing.any { permission ->
            !shouldShowRequestPermissionRationale(permission)
        }
        if (permanentlyDenied) {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                },
            )
        } else if (missing.isNotEmpty()) {
            val only = catalog.list().singleOrNull()
            if (only?.captureCard == true) pending = only
            permissionRequest.launch(missing)
        }
    }

    private fun renderPermission() {
        val missing = missingPermissions()
        val show = missing.isNotEmpty()
        binding.permissionNote.visibility = if (show) View.VISIBLE else View.GONE
        binding.permissionButton.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val permanentlyDenied = permissionDenied && missing.any { permission ->
            !shouldShowRequestPermissionRationale(permission)
        }
        binding.permissionButton.setText(
            if (permanentlyDenied) R.string.open_settings else R.string.allow_access,
        )
    }

    private fun missingPermissions(): Array<String> {
        return arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filterNot(::has)
            .toTypedArray()
    }

    private fun has(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val POLL_MS = 2_000L
    }
}
