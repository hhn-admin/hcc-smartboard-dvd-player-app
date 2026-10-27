package com.hcc.dvdplayer

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
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
import com.hcc.dvdplayer.logic.CaptureSourceFilter
import com.hcc.dvdplayer.logic.LaunchChoice
import com.hcc.dvdplayer.logic.ManagedSettings
import com.hcc.dvdplayer.logic.PickerPrompt
import com.hcc.dvdplayer.logic.RuntimePermissions
import com.hcc.dvdplayer.source.AppSettings
import com.hcc.dvdplayer.source.LastSourceStore
import com.hcc.dvdplayer.source.ListedSource
import com.hcc.dvdplayer.source.SourceCatalog
import com.hcc.dvdplayer.source.UsbEvents
import com.hcc.dvdplayer.ui.SourceAdapter

class PickerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPickerBinding
    private lateinit var catalog: SourceCatalog
    private lateinit var appSettings: AppSettings
    private lateinit var lastSource: LastSourceStore
    private var settings: ManagedSettings = ManagedSettings.parse(
        com.hcc.dvdplayer.logic.RestrictionInput(emptySet()),
        localLock = false,
    )
    private val adapter = SourceAdapter(::onSourceClicked)
    private val handler = Handler(Looper.getMainLooper())
    private var signature = ""
    private var pending: ListedSource? = null
    private var permissionDenied = false
    private var returnedFromPlayer = false
    private var openInProgress = false
    private var inRefresh = false
    private var closedForExcludedUsb = false

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val source = pending
        pending = null
        val stillMissing = if (source != null) {
            RuntimePermissions.requiredFor(source).filterNot(::has)
        } else {
            missingPermissions(catalog.list(settings.exclusionPolicy))
        }
        permissionDenied = stillMissing.isNotEmpty()
        if (stillMissing.isEmpty()) {
            returnedFromPlayer = false
            if (source != null) {
                open(source)
            } else {
                refresh()
            }
        } else {
            refresh()
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

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            refresh()
        }
    }

    private val restrictionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (closedForExcludedUsb || !::appSettings.isInitialized) return
            settings = appSettings.current()
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
        if (isExcludedUsbAttach(intent)) {
            closedForExcludedUsb = true
            finish()
            return
        }
        binding = ActivityPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        catalog = SourceCatalog(this)
        appSettings = AppSettings(this)
        lastSource = LastSourceStore(this)
        settings = appSettings.current()
        binding.sources.layoutManager = LinearLayoutManager(this)
        binding.sources.adapter = adapter
        binding.permissionButton.setOnClickListener { requestAccess() }
        keepEmptyPromptAboveSystemBars()
        UsbEvents.register(this, usbReceiver)
        appSettings.registerRestrictionsListener(this, restrictionsReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (closedForExcludedUsb) return
        if (isExcludedUsbAttach(intent)) return
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            returnedFromPlayer = false
            refresh()
        }
    }

    override fun onStart() {
        super.onStart()
        if (closedForExcludedUsb) return
        refresh()
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, POLL_MS)
    }

    override fun onStop() {
        if (!closedForExcludedUsb) {
            handler.removeCallbacks(poll)
            binding.emptyArrow.clearAnimation()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (!closedForExcludedUsb) {
            unregisterReceiver(usbReceiver)
            unregisterReceiver(restrictionsReceiver)
        }
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
            settings = appSettings.current()
            val sources = catalog.list(settings.exclusionPolicy)
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
            renderPermission(sources)
            val granted = grantedPermissions()
            val remembered = lastSource.load()
            val choice = LaunchChoice.choose(
                sources = sources,
                grantedPermissions = granted,
                returnedFromPlayer = returnedFromPlayer,
                openInProgress = openInProgress,
                autoStart = settings.autoStart,
                remembered = remembered,
            )
            if (choice != null) open(choice)
            val stillEligible = LaunchChoice.choose(
                sources = sources,
                grantedPermissions = granted,
                returnedFromPlayer = false,
                openInProgress = false,
                autoStart = settings.autoStart,
                remembered = remembered,
            )
            if (stillEligible == null) returnedFromPlayer = false
        } finally {
            inRefresh = false
        }
    }

    private fun onSourceClicked(source: ListedSource) {
        binding.notice.visibility = View.GONE
        val missing = RuntimePermissions.requiredFor(source).filterNot(::has)
        if (missing.isEmpty()) {
            returnedFromPlayer = false
            open(source)
        } else {
            pending = source
            permissionRequest.launch(missing.toTypedArray())
        }
    }

    private fun open(source: ListedSource) {
        if (openInProgress) return
        openInProgress = true
        if (source.kind == ListedSource.Kind.CAMERA2_EXTERNAL && !has(RuntimePermissions.CAMERA)) {
            openInProgress = false
            binding.notice.setText(R.string.camera_permission_required)
            binding.notice.visibility = View.VISIBLE
            renderPermission(catalog.list(settings.exclusionPolicy))
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
        val sources = catalog.list(settings.exclusionPolicy)
        val missing = missingPermissions(sources)
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
            val only = sources.singleOrNull()
            if (only?.captureCard == true) pending = only
            permissionRequest.launch(missing.toTypedArray())
        }
    }

    private fun renderPermission(sources: List<ListedSource>) {
        val missing = missingPermissions(sources)
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

    private fun missingPermissions(sources: List<ListedSource>): List<String> {
        return sources
            .flatMap { RuntimePermissions.requiredFor(it) }
            .distinct()
            .filterNot(::has)
    }

    private fun grantedPermissions(): Set<String> {
        return setOf(RuntimePermissions.CAMERA, RuntimePermissions.RECORD_AUDIO).filter(::has).toSet()
    }

    /**
     * device_filter.xml matches every UVC class device, including the SMART
     * webcam, because a filter cannot exclude a vendor id. Ignore that attach.
     * If it is what launched the activity, close so the webcam does not leave
     * DVD Player on screen.
     */
    private fun isExcludedUsbAttach(intent: Intent?): Boolean {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return false
        val device = UsbEvents.device(intent) ?: return false
        val policy = if (::appSettings.isInitialized) {
            appSettings.current().exclusionPolicy
        } else {
            AppSettings(this).current().exclusionPolicy
        }
        return CaptureSourceFilter.isExcludedUsb(
            device.vendorId,
            quietName { device.productName },
            quietName { device.manufacturerName },
            policy = policy,
        )
    }

    private fun quietName(read: () -> String?): String? {
        return try {
            read()?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun has(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val POLL_MS = 2_000L
    }
}
