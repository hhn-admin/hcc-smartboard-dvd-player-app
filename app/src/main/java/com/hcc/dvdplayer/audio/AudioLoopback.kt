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
import android.hardware.usb.UsbConstants
import com.hcc.dvdplayer.logic.AudioFormatPlanner
import com.hcc.dvdplayer.logic.CaptureSourceFilter
import com.hcc.dvdplayer.logic.ExclusionPolicy
import com.hcc.dvdplayer.logic.PcmDelayLine
import com.hcc.dvdplayer.logic.PcmLevel
import com.hcc.dvdplayer.logic.UacPlayback

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
 *
 * [RECORD_AUDIO] is required only for [AudioRecord]. The direct isochronous
 * reader uses the USB device permission the video path already holds. When the
 * microphone permission is already granted, [AudioRecord] stays first: that is
 * the path confirmed on the board. Otherwise the direct reader runs, and the
 * listener is asked for [RECORD_AUDIO] only if that reader never delivers PCM.
 */
class AudioLoopback(
    context: Context,
    private val productHint: String?,
    private val usbDeviceName: String?,
    private val syncDelayMs: Int,
    private val exclusionPolicy: ExclusionPolicy = ExclusionPolicy.DEFAULT,
    private val listener: (Status) -> Unit,
) {
    enum class Status {
        STARTING,
        PLAYING,
        NO_USB_INPUT,
        PERMISSION_DENIED,
        NEEDS_RECORD_AUDIO,
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
    private var quietPolls = 0
    @Volatile private var directUsbNote: String = "not started"
    @Volatile private var pcmLevel = 0f
    @Volatile private var muted = false

    fun directUsbNote(): String = directUsbNote

    /** Latest PCM peak, 0 for silence and 1 for full scale. */
    fun level(): Float = pcmLevel

    fun isMuted(): Boolean = muted

    /** Silences the speakers. Capture keeps running so unmute does not renegotiate USB audio. */
    fun setMuted(value: Boolean) {
        muted = value
        applyGain()
    }

    fun start() {
        if (running) return
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
        thread?.join(2_500)
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
            if (!recordAudioGranted()) {
                if (!runDirectWithoutMicrophone()) return
                continue
            }
            if (!runWithMicrophonePermission()) return
        }
    }

    /**
     * No [RECORD_AUDIO] yet. Try the capture card's own UAC interface. Ask for
     * the microphone permission only when that reader never delivers PCM.
     * A reader that already played and then stopped is retried without a prompt.
     */
    private fun runDirectWithoutMicrophone(): Boolean {
        val pumped = try {
            directUsbNote = "opening the capture card's USB audio"
            tryDirectUsb(requirePcm = true)
        } catch (error: Exception) {
            Log.e(TAG, "direct usb audio", error)
            directUsbNote = "direct USB audio failed: ${error.message ?: error.javaClass.simpleName}"
            false
        }
        releaseStream()
        if (!running) return false
        if (pumped) return true
        if (status == Status.PLAYING) {
            directUsbNote = "direct USB audio stopped; retrying"
            publish(Status.NO_USB_INPUT)
            pauseBriefly()
            return true
        }
        directUsbNote = "direct USB audio did not play; microphone permission needed"
        running = false
        publish(Status.NEEDS_RECORD_AUDIO)
        return false
    }

    private fun runWithMicrophonePermission(): Boolean {
        val inputs = usbInputs()
        if (inputs.isEmpty()) {
            quietPolls += 1
            if (quietPolls == 1) {
                directUsbNote = "waiting for a USB input"
                publish(Status.STARTING)
                waitForUsbInput()
                return running
            }
            val pumped = try {
                directUsbNote = "opening the capture card's USB audio"
                tryDirectUsb(requirePcm = false)
            } catch (error: Exception) {
                Log.e(TAG, "direct usb audio", error)
                directUsbNote = "direct USB audio failed: ${error.message ?: error.javaClass.simpleName}"
                false
            }
            releaseStream()
            if (!running) return false
            if (pumped) {
                quietPolls = 0
                return true
            }
            if (directUsbNote.startsWith("opening")) {
                directUsbNote = "no USB input, and direct USB audio did not start"
            }
            publish(Status.NO_USB_INPUT)
            waitForUsbInput()
            return running
        }
        quietPolls = 0
        directUsbNote = "using Android USB input"
        val pumped = try {
            openAndPump(inputs)
        } catch (error: SecurityException) {
            Log.e(TAG, "record permission", error)
            running = false
            publish(Status.PERMISSION_DENIED)
            return false
        } catch (error: Exception) {
            Log.e(TAG, "audio loop", error)
            false
        }
        releaseStream()
        if (!running) return false
        if (pumped) return true
        val direct = try {
            tryDirectUsb(requirePcm = false)
        } catch (error: Exception) {
            Log.e(TAG, "direct usb audio", error)
            directUsbNote = "direct USB audio failed: ${error.message ?: error.javaClass.simpleName}"
            false
        }
        releaseStream()
        if (!running) return false
        if (direct) return true
        publish(Status.FAILED)
        pauseBriefly()
        return running
    }

    private fun recordAudioGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun tryDirectUsb(requirePcm: Boolean): Boolean {
        val target = CaptureSourceFilter.directUsbDeviceName(
            usbDeviceName,
            connectedUsbDevices(),
            exclusionPolicy,
        )
        if (target == null) {
            directUsbNote = "no capture-card audio interface to open"
            Log.w(TAG, directUsbNote)
            return false
        }
        val capture = UsbUacCapture(appContext, target, exclusionPolicy)
        val session = capture.open()
        if (session == null) {
            directUsbNote = "direct USB audio did not open"
            return false
        }
        directUsbNote = "direct ${session.summary}"
        try {
            val plan = AudioFormatPlanner.plan(
                reportedRates = intArrayOf(session.sampleRate),
                reportedChannels = intArrayOf(session.channelCount),
                outputPreferredRate = session.sampleRate,
                delayMs = syncDelayMs,
            )
            val outMask = if (plan.channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minTrack = AudioTrack.getMinBufferSize(plan.sampleRate, outMask, AudioFormat.ENCODING_PCM_16BIT)
            if (minTrack <= 0) return false
            val attributes = mediaAttributes()
            val playFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(plan.sampleRate)
                .setChannelMask(outMask)
                .build()
            val audioTrack = buildTrack(attributes, playFormat, maxOf(minTrack, plan.frameBytes * plan.sampleRate / 50), lowLatency = true)
                ?: buildTrack(attributes, playFormat, minTrack, lowLatency = false)
                ?: return false
            track = audioTrack
            requestFocus(attributes)
            Log.i(TAG, "Direct USB audio ${session.summary} delay=${syncDelayMs}ms")
            return pumpFrom(session::read, audioTrack, plan, requirePcm = requirePcm)
        } finally {
            session.close()
        }
    }

    private fun openAndPump(inputs: List<AudioDeviceInfo>): Boolean {
        val outputRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        for (device in inputs) {
            if (UacPlayback.halReportsMono96k(device.sampleRates, device.channelCounts)) {
                Log.i(TAG, "USB audio ${device.productName} reports 96 kHz mono; playing it as 48 kHz stereo")
                for (source in RECORD_SOURCES) {
                    val opened = openStream(device, source, 96_000, 1, 48_000, 2) ?: continue
                    if (playOpened(device, opened, source)) return true
                }
                continue
            }
            val rates = AudioFormatPlanner.rateAttempts(device.sampleRates, outputRate)
            val channels = AudioFormatPlanner.channelAttempts(device.channelCounts)
            for (rate in rates) {
                for (channelCount in channels) {
                    for (source in RECORD_SOURCES) {
                        val opened = openStream(device, source, rate, channelCount) ?: continue
                        if (playOpened(device, opened, source)) return true
                    }
                }
            }
        }
        return false
    }

    private fun playOpened(device: AudioDeviceInfo, opened: Opened, source: Int): Boolean {
        Log.i(
            TAG,
            "USB audio ${device.productName} ${opened.plan.sampleRate}Hz " +
                "ch=${opened.plan.channelCount} source=$source delay=${syncDelayMs}ms",
        )
        directUsbNote = "Android USB input ${opened.plan.sampleRate} Hz x ${opened.plan.channelCount}"
        requestFocus(opened.attributes)
        val ok = pump(opened.record, opened.track, opened.plan)
        if (!running) return true
        if (ok) return true
        releaseStream()
        return false
    }

    private fun openStream(
        device: AudioDeviceInfo,
        source: Int,
        sampleRate: Int,
        channelCount: Int,
        playRate: Int = sampleRate,
        playChannels: Int = channelCount,
    ): Opened? {
        val plan = AudioFormatPlanner.plan(
            reportedRates = intArrayOf(playRate),
            reportedChannels = intArrayOf(playChannels),
            outputPreferredRate = playRate,
            delayMs = syncDelayMs,
        )
        val inMask = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val outMask = if (playChannels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minRecord = AudioRecord.getMinBufferSize(sampleRate, inMask, AudioFormat.ENCODING_PCM_16BIT)
        val minTrack = AudioTrack.getMinBufferSize(playRate, outMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minRecord <= 0 || minTrack <= 0) return null
        val recordFrame = channelCount * 2
        val recordBuffer = maxOf(minRecord, recordFrame * sampleRate / 50) * 2
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
            .setSampleRate(playRate)
            .setChannelMask(outMask)
            .build()
        val preferredBuffer = maxOf(minTrack, plan.frameBytes * playRate / 50)
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
        return pumpFrom(
            read = { buffer -> audioRecord.read(buffer, 0, buffer.size) },
            audioTrack = audioTrack,
            plan = plan,
            afterPreroll = {
                val routedAfter = audioRecord.routedDevice
                if (routedAfter != null && !isAllowedCaptureRoute(routedAfter)) {
                    Log.w(TAG, "Route drifted off the capture card type=${routedAfter.type} (${routedAfter.productName})")
                    false
                } else {
                    true
                }
            },
        )
    }

    private fun pumpFrom(
        read: (ByteArray) -> Int,
        audioTrack: AudioTrack,
        plan: AudioFormatPlanner.Plan,
        requirePcm: Boolean = false,
        afterPreroll: () -> Boolean = { true },
    ): Boolean {
        val line = PcmDelayLine(plan.delayBytes)
        val chunk = (plan.sampleRate / 100) * plan.frameBytes
        val tmp = ByteArray(chunk.coerceAtLeast(plan.frameBytes))
        val prerollDeadline = SystemClock.elapsedRealtime() + 800
        while (running && !line.primed() && SystemClock.elapsedRealtime() < prerollDeadline) {
            val got = read(tmp)
            if (got < 0) return false
            if (got > 0) {
                pcmLevel = PcmLevel.peakUnit(tmp, got)
                line.write(tmp, 0, got)
            }
        }
        if (requirePcm && !line.primed()) {
            directUsbNote = "direct USB audio produced no PCM"
            Log.w(TAG, directUsbNote)
            return false
        }
        if (!afterPreroll()) return false
        try {
            audioTrack.play()
            applyGain()
        } catch (error: Exception) {
            Log.w(TAG, "track.play", error)
            return false
        }
        publish(Status.PLAYING)
        while (running) {
            val got = read(tmp)
            if (got < 0) return false
            if (got == 0) {
                pcmLevel = 0f
                continue
            }
            pcmLevel = PcmLevel.peakUnit(tmp, got)
            line.write(tmp, 0, got)
            if (line.queued() > plan.delayBytes * 4) line.trimTo(plan.delayBytes)
            if (!line.primed()) continue
            val out = line.read(got)
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
        return !CaptureSourceFilter.isBlockedAudio(
            identity,
            connectedUsbDevices(),
            productHint,
            usbDeviceName,
            exclusionPolicy,
        )
    }

    private fun usbInputs(): List<AudioDeviceInfo> {
        val raw = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { isUsbInput(it.type) }
        val identities = raw.map { info ->
            CaptureSourceFilter.AudioInputIdentity(
                productName = info.productName?.toString(),
                address = audioAddress(info),
            )
        }
        return CaptureSourceFilter.selectAudioInputIndexes(
            identities,
            productHint,
            connectedUsbDevices(),
            usbDeviceName,
            exclusionPolicy,
        )
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
                productId = device.productId,
                hasAudioInterface = hasAudioInterface(device),
            )
        }
    }

    private fun hasAudioInterface(device: android.hardware.usb.UsbDevice): Boolean {
        return try {
            (0 until device.interfaceCount).any { index ->
                device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        } catch (_: Exception) {
            false
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

    private fun applyGain() {
        val current = track
        if (current == null) return
        try {
            current.setVolume(if (muted) 0f else 1f)
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
