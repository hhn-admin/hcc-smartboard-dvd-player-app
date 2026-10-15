package com.hcc.dvdplayer.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.hcc.dvdplayer.logic.AudioFormatPlanner
import com.hcc.dvdplayer.logic.CaptureSourceFilter
import com.hcc.dvdplayer.logic.PcmDelayLine

/**
 * Reads the capture card's USB audio interface and writes it to an [AudioTrack]
 * with [AudioAttributes.USAGE_MEDIA]. Media usage follows the board's normal
 * output (speakers or HDMI out) and is what screen-share / cast apps are allowed
 * to capture. The track is never pinned to the USB device, or the sound would
 * be sent back into the dongle.
 *
 * The built-in microphone is refused. Looping that into the room speakers is a
 * feedback howl, and it is not the capture card. USB vendor 0x0B8C (the SMART
 * Ultra HD Camera and its mic array) is refused as well.
 */
class AudioLoopback(
    context: Context,
    private val productHint: String?,
    private val syncDelayMs: Int,
    private val listener: (Status) -> Unit,
) {
    enum class Status {
        STARTING,
        PLAYING,
        NO_USB_INPUT,
        PERMISSION_DENIED,
        FAILED,
        STOPPED,
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Object()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var focusRequest: android.media.AudioFocusRequest? = null
    private var status = Status.STOPPED

    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            publish(Status.PERMISSION_DENIED)
            return
        }
        running = true
        publish(Status.STARTING)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                signal()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                signal()
            }
        }
        deviceCallback = callback
        audioManager.registerAudioDeviceCallback(callback, mainHandler)
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            runLoop()
        }, "hdmi-audio").also { it.start() }
    }

    fun stop() {
        running = false
        signal()
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            track?.pause()
        } catch (_: Exception) {
        }
        thread?.join(1_500)
        thread = null
        releaseStream()
        deviceCallback?.let { callback ->
            try {
                audioManager.unregisterAudioDeviceCallback(callback)
            } catch (_: Exception) {
            }
        }
        deviceCallback = null
        abandonFocus()
        publish(Status.STOPPED)
    }

    private fun runLoop() {
        while (running) {
            val inputs = usbInputs()
            if (inputs.isEmpty()) {
                publish(Status.NO_USB_INPUT)
                waitForUsbInput()
                continue
            }
            val pumped = try {
                openAndPump(inputs)
            } catch (error: SecurityException) {
                Log.e(TAG, "record permission", error)
                publish(Status.PERMISSION_DENIED)
                return
            } catch (error: Exception) {
                Log.e(TAG, "audio loop", error)
                false
            }
            releaseStream()
            if (!running) break
            if (!pumped) {
                publish(Status.FAILED)
                pauseBriefly()
            }
        }
    }

    private fun openAndPump(inputs: List<AudioDeviceInfo>): Boolean {
        val ordered = inputs
        val outputRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        for (device in ordered) {
            val rates = AudioFormatPlanner.rateAttempts(device.sampleRates, outputRate)
            val channels = AudioFormatPlanner.channelAttempts(device.channelCounts)
            for (rate in rates) {
                for (channelCount in channels) {
                    for (source in RECORD_SOURCES) {
                        val opened = openStream(device, source, rate, channelCount) ?: continue
                        Log.i(
                            TAG,
                            "USB audio ${device.productName} ${opened.plan.sampleRate}Hz " +
                                "ch=${opened.plan.channelCount} source=$source delay=${syncDelayMs}ms",
                        )
                        requestFocus(opened.attributes)
                        val ok = pump(opened.record, opened.track, opened.plan)
                        if (!running) return true
                        if (ok) return true
                        releaseStream()
                    }
                }
            }
        }
        return false
    }

    private fun openStream(
        device: AudioDeviceInfo,
        source: Int,
        sampleRate: Int,
        channelCount: Int,
    ): Opened? {
        val plan = AudioFormatPlanner.plan(
            reportedRates = intArrayOf(sampleRate),
            reportedChannels = intArrayOf(channelCount),
            outputPreferredRate = sampleRate,
            delayMs = syncDelayMs,
        )
        val inMask = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val outMask = if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minRecord = AudioRecord.getMinBufferSize(sampleRate, inMask, AudioFormat.ENCODING_PCM_16BIT)
        val minTrack = AudioTrack.getMinBufferSize(sampleRate, outMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minRecord <= 0 || minTrack <= 0) return null
        val recordBuffer = maxOf(minRecord, plan.frameBytes * sampleRate / 50) * 2
        val recordFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(inMask)
            .build()
        val audioRecord = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(recordFormat)
                .setBufferSizeInBytes(recordBuffer)
                .build()
        } catch (error: Exception) {
            Log.d(TAG, "AudioRecord init failed source=$source rate=$sampleRate ch=$channelCount", error)
            return null
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return null
        }
        if (!audioRecord.setPreferredDevice(device)) {
            audioRecord.release()
            return null
        }
        val attributes = mediaAttributes()
        val playFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(outMask)
            .build()
        val preferredBuffer = maxOf(minTrack, plan.frameBytes * sampleRate / 50)
        val audioTrack = buildTrack(attributes, playFormat, preferredBuffer, lowLatency = true)
            ?: buildTrack(attributes, playFormat, minTrack, lowLatency = false)
        if (audioTrack == null) {
            audioRecord.release()
            return null
        }
        record = audioRecord
        track = audioTrack
        return Opened(audioRecord, audioTrack, attributes, plan)
    }

    private fun buildTrack(
        attributes: AudioAttributes,
        format: AudioFormat,
        bufferSize: Int,
        lowLatency: Boolean,
    ): AudioTrack? {
        return try {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val created = builder.build()
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                null
            } else {
                created
            }
        } catch (error: Exception) {
            Log.d(TAG, "AudioTrack init failed", error)
            null
        }
    }

    private fun pump(audioRecord: AudioRecord, audioTrack: AudioTrack, plan: AudioFormatPlanner.Plan): Boolean {
        try {
            audioRecord.startRecording()
        } catch (error: Exception) {
            Log.w(TAG, "startRecording", error)
            return false
        }
        val routed = audioRecord.routedDevice
        if (routed != null && !isAllowedCaptureRoute(routed)) {
            Log.w(TAG, "Refusing route type=${routed.type} (${routed.productName})")
            return false
        }
        val line = PcmDelayLine(plan.delayBytes)
        val chunk = (plan.sampleRate / 100) * plan.frameBytes
        val tmp = ByteArray(chunk.coerceAtLeast(plan.frameBytes))
        val prerollDeadline = SystemClock.elapsedRealtime() + 800
        while (running && !line.primed() && SystemClock.elapsedRealtime() < prerollDeadline) {
            val read = audioRecord.read(tmp, 0, tmp.size)
            if (read < 0) return false
            if (read > 0) line.write(tmp, 0, read)
        }
        val routedAfter = audioRecord.routedDevice
        if (routedAfter != null && !isAllowedCaptureRoute(routedAfter)) {
            Log.w(TAG, "Route drifted off the capture card type=${routedAfter.type} (${routedAfter.productName})")
            return false
        }
        try {
            audioTrack.play()
        } catch (error: Exception) {
            Log.w(TAG, "track.play", error)
            return false
        }
        publish(Status.PLAYING)
        while (running) {
            val read = audioRecord.read(tmp, 0, tmp.size)
            if (read < 0) return false
            if (read == 0) continue
            line.write(tmp, 0, read)
            if (line.queued() > plan.delayBytes * 4) line.trimTo(plan.delayBytes)
            if (!line.primed()) continue
            val out = line.read(read)
            var offset = 0
            while (offset < out.size && running) {
                val wrote = audioTrack.write(out, offset, out.size - offset)
                if (wrote < 0) return false
                if (wrote == 0) break
                offset += wrote
            }
        }
        return true
    }

    private fun waitForUsbInput() {
        pauseBriefly()
    }

    private fun pauseBriefly() {
        synchronized(lock) {
            if (!running) return
            lock.wait(1_500)
        }
    }

    private fun isAllowedCaptureRoute(info: AudioDeviceInfo): Boolean {
        if (!isUsbInput(info.type)) return false
        val identity = CaptureSourceFilter.AudioInputIdentity(
            productName = info.productName?.toString(),
            address = audioAddress(info),
        )
        return !CaptureSourceFilter.isBlockedAudio(identity, connectedUsbDevices(), productHint)
    }

    private fun usbInputs(): List<AudioDeviceInfo> {
        val raw = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { isUsbInput(it.type) }
        val identities = raw.map { info ->
            CaptureSourceFilter.AudioInputIdentity(
                productName = info.productName?.toString(),
                address = audioAddress(info),
            )
        }
        return CaptureSourceFilter.selectAudioInputIndexes(identities, productHint, connectedUsbDevices())
            .map { index -> raw[index] }
    }

    private fun connectedUsbDevices(): List<CaptureSourceFilter.UsbIdentity> {
        val manager = appContext.getSystemService(UsbManager::class.java) ?: return emptyList()
        return manager.deviceList.values.map { device ->
            CaptureSourceFilter.UsbIdentity(
                vendorId = device.vendorId,
                deviceName = device.deviceName,
                productName = quietName { device.productName },
                manufacturerName = quietName { device.manufacturerName },
            )
        }
    }

    private fun audioAddress(info: AudioDeviceInfo): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.address else null
    }

    private fun quietName(read: () -> String?): String? {
        return try {
            read()?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun mediaAttributes(): AudioAttributes {
        val builder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
        }
        return builder.build()
    }

    private fun requestFocus(attributes: AudioAttributes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun releaseStream() {
        val currentRecord = record
        val currentTrack = track
        record = null
        track = null
        try {
            currentRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            currentRecord?.release()
        } catch (_: Exception) {
        }
        try {
            currentTrack?.pause()
            currentTrack?.flush()
            currentTrack?.release()
        } catch (_: Exception) {
        }
    }

    private fun publish(next: Status) {
        if (status == next) return
        status = next
        mainHandler.post { listener(next) }
    }

    private fun signal() {
        synchronized(lock) { lock.notifyAll() }
    }

    private data class Opened(
        val record: AudioRecord,
        val track: AudioTrack,
        val attributes: AudioAttributes,
        val plan: AudioFormatPlanner.Plan,
    )

    companion object {
        private const val TAG = "HdmiIn"
        private val RECORD_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.DEFAULT,
        )

        fun isUsbInput(type: Int): Boolean {
            if (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_ACCESSORY) {
                return true
            }
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }
}
