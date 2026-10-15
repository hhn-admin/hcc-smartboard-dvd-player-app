package com.hcc.dvdplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.hcc.dvdplayer.databinding.ActivityPickerBinding
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

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val source = pending
        pending = null
        permissionDenied = missingPermissions().isNotEmpty()
        renderPermission()
        if (source != null) open(source)
    }

    private val player = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun refresh() {
        val sources = catalog.list()
        val nextSignature = sources.joinToString("\n") { it.signature() }
        if (nextSignature != signature) {
            signature = nextSignature
            adapter.submit(sources)
        }
        binding.empty.visibility = if (sources.isEmpty()) View.VISIBLE else View.GONE
        binding.sources.visibility = if (sources.isEmpty()) View.GONE else View.VISIBLE
        binding.empty.text = when {
            !catalog.usbHostAvailable() -> getString(R.string.empty_no_usb_host)
            else -> getString(R.string.empty_sources)
        }
        if (sources.size == 1) {
            binding.subtitle.setText(R.string.picker_subtitle_one)
        } else {
            binding.subtitle.setText(R.string.picker_subtitle)
        }
        renderPermission()
    }

    private fun onSourceClicked(source: ListedSource) {
        binding.notice.visibility = View.GONE
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            open(source)
        } else {
            pending = source
            permissionRequest.launch(missing)
        }
    }

    private fun open(source: ListedSource) {
        if (source.kind == ListedSource.Kind.CAMERA2_EXTERNAL && !has(Manifest.permission.CAMERA)) {
            binding.notice.setText(R.string.camera_permission_required)
            binding.notice.visibility = View.VISIBLE
            renderPermission()
            return
        }
        val intent = Intent(this, PlayerActivity::class.java)
        when (source.kind) {
            ListedSource.Kind.USB_UVC -> {
                val name = source.usbDeviceName ?: return
                if (catalog.findUsbDevice(name) == null) {
                    binding.notice.setText(R.string.device_unplugged)
                    binding.notice.visibility = View.VISIBLE
                    refresh()
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
