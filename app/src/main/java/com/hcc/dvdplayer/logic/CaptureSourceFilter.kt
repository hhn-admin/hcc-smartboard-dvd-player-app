package com.hcc.dvdplayer.logic

/**
 * SMART boards keep a SMART Ultra HD Camera 120 (SWC-120UHD) plugged in.
 * That webcam is USB vendor 0x0B8C. It must never be listed, auto-started, or
 * used as the audio loopback. HDMI capture dongles are chosen by name.
 * Their UAC function is often a generic "USB Audio" name on the same device,
 * which is kept when it is the only capture card with an audio interface.
 */
object CaptureSourceFilter {
    const val SMART_VENDOR_ID = 0x0B8C

    private val EXCLUDED_PHRASES = listOf(
        "smart technologies",
        "ultra hd camera",
        "swc-120",
        "swc120",
        "webcam",
        "integrated",
        "smart",
    )

    private val CAPTURE_PHRASES = listOf(
        "capture",
        "hdmi",
        "grabber",
        "elgato",
        "ezcap",
        "ms210",
        "ms213",
        "mack",
        "uvc",
        "avg",
    )

    private val GENERIC_UAC_PREFIXES = listOf(
        "usb audio",
        "usb pnp sound",
        "usb headset",
        "digital audio",
        "hdmi audio",
    )

    data class UsbIdentity(
        val vendorId: Int,
        val deviceName: String?,
        val productName: String?,
        val manufacturerName: String?,
        val productId: Int = 0,
        val hasAudioInterface: Boolean = false,
    )

    data class AudioInputIdentity(
        val productName: String?,
        val address: String?,
    )

    data class AudioChoice(
        val use: Boolean,
        val detail: String,
    )

    fun isExcludedUsb(
        vendorId: Int,
        vararg names: String?,
        policy: ExclusionPolicy = ExclusionPolicy.DEFAULT,
    ): Boolean {
        if (vendorId in policy.extraVendorIds) return true
        if (!policy.excludeSmartDevices) return false
        if (vendorId == SMART_VENDOR_ID) return true
        return names.any { matchesExcludedName(it) }
    }

    fun looksLikeCaptureCard(
        vararg names: String?,
        policy: ExclusionPolicy = ExclusionPolicy.DEFAULT,
    ): Boolean {
        if (policy.excludeSmartDevices && names.any { matchesExcludedName(it) }) return false
        return names.any { matchesCaptureName(it) }
    }

    /**
     * When any remaining device looks like a capture card, those are the only
     * rows. Otherwise keep USB video devices that are not the SMART webcam.
     */
    fun <T> selectUsbVideo(
        devices: List<T>,
        vendorId: (T) -> Int,
        productName: (T) -> String?,
        manufacturerName: (T) -> String?,
        policy: ExclusionPolicy = ExclusionPolicy.DEFAULT,
    ): List<T> {
        val allowed = devices.filter { device ->
            !isExcludedUsb(vendorId(device), productName(device), manufacturerName(device), policy = policy)
        }
        val captures = allowed.filter { device ->
            looksLikeCaptureCard(productName(device), manufacturerName(device), policy = policy)
        }
        return if (captures.isNotEmpty()) captures else allowed
    }

    /**
     * Camera2 is shown only when no USB source was kept and the camera name
     * itself looks like a capture card. The SMART webcam and built-in cameras
     * do not.
     */
    fun allowCamera2(
        labels: List<String?>,
        listedUsbCount: Int,
        policy: ExclusionPolicy = ExclusionPolicy.DEFAULT,
    ): Boolean {
        if (listedUsbCount > 0) return false
        return looksLikeCaptureCard(*labels.toTypedArray(), policy = policy)
    }

    fun selectAudioInputIndexes(
        inputs: List<AudioInputIdentity>,
        productHint: String?,
        usbDevices: List<UsbIdentity>,
        playingDeviceName: String? = null,
        policy: ExclusionPolicy = ExclusionPolicy.DEFAULT,
    ): List<Int> {
        val allowed = inputs.indices.filter { index ->
            chooseAudio(inputs[index], usbDevices, productHint, playingDeviceName, policy).use
        }
        val hint = productHint?.trim().orEmpty()
        if (hint.isEmpty()) return allowed
        val matched = allowed.filter { index ->
            inputs[index].productName?.contains(hint, ignoreCase = true) == true
        }
        return if (matched.isEmpty()) allowed else matched
    }

    /**
     * Cheap HDMI sticks (Audio Express AXHDCAP and the same MacroSilicon-class
     * layout) often leave the audio function unnamed, or name it "USB Audio",
     * while Android reports the input address as `card=N;device=M`. That does
     * not equal the video device's `/dev/bus/usb/...` path, and the video
     * product string is frequently empty. While the SMART webcam is on the bus,
     * those inputs are still the capture card when it is the only other USB
     * device with a UAC interface.
     */
    fun chooseAudio(
        input: AudioInputIdentity,
        usbDevices: List<UsbIdentity>,
        productHint: String? = null,
        playingDeviceName: String? = null,
        policy: ExclusionPolicy = ExclusionPolicy.DEFAULT,
    ): AudioChoice {
        if (policy.excludeSmartDevices && matchesExcludedName(input.productName)) {
            return AudioChoice(false, "excluded name")
        }
        val blockedDevices = usbDevices.filter { device ->
            isExcludedUsb(device.vendorId, device.productName, device.manufacturerName, policy = policy)
        }
        if (blockedDevices.any { device -> tied(input, device) }) {
            return AudioChoice(false, "matches the SMART webcam")
        }
        val smartPresent = policy.excludeSmartDevices && usbDevices.any { it.vendorId == SMART_VENDOR_ID }
        val extraPresent = usbDevices.any { it.vendorId in policy.extraVendorIds }
        if (!smartPresent && !extraPresent) {
            return AudioChoice(true, "no SMART webcam on USB")
        }
        val captures = usbDevices.filter { device ->
            !isExcludedUsb(device.vendorId, device.productName, device.manufacturerName, policy = policy)
        }
        val hint = productHint?.trim().orEmpty()
        if (hint.length >= 4 && input.productName?.contains(hint, ignoreCase = true) == true) {
            return AudioChoice(true, "product name matches the capture card")
        }
        val playing = captures.firstOrNull { device ->
            !device.deviceName.isNullOrBlank() && device.deviceName == playingDeviceName
        }
        if (playing != null && tied(input, playing)) {
            return AudioChoice(true, "matches the playing capture card")
        }
        if (captures.any { device -> tied(input, device) }) {
            return AudioChoice(true, "matches a capture card")
        }
        val audioCaptures = captures.filter { it.hasAudioInterface }
        if (isGenericUacName(input.productName) && audioCaptures.size == 1) {
            val only = audioCaptures[0]
            val playingOk = playingDeviceName.isNullOrBlank() || playingDeviceName == only.deviceName
            if (playingOk) {
                return AudioChoice(true, "generic USB audio from the capture card")
            }
        }
        return AudioChoice(false, "SMART webcam is connected and this input is not the capture card")
    }

    fun audioDecision(
        input: AudioInputIdentity,
        usbDevices: List<UsbIdentity>,
        productHint: String? = null,
        playingDeviceName: String? = null,
        policy: ExclusionPolicy = ExclusionPolicy.DEFAULT,
    ): String {
        val choice = chooseAudio(input, usbDevices, productHint, playingDeviceName, policy)
        return if (choice.use) "use: ${choice.detail}" else "blocked: ${choice.detail}"
    }

    fun isBlockedAudio(
        input: AudioInputIdentity,
        usbDevices: List<UsbIdentity>,
        productHint: String? = null,
        playingDeviceName: String? = null,
        policy: ExclusionPolicy = ExclusionPolicy.DEFAULT,
    ): Boolean {
        return !chooseAudio(input, usbDevices, productHint, playingDeviceName, policy).use
    }

    /**
     * USB device to read from directly when Android's audio HAL lists no input.
     * Prefers the device that is already playing video, and otherwise the only
     * non-SMART device that actually has a UAC interface.
     */
    fun directUsbDeviceName(
        playingDeviceName: String?,
        usbDevices: List<UsbIdentity>,
        policy: ExclusionPolicy = ExclusionPolicy.DEFAULT,
    ): String? {
        val playing = usbDevices.firstOrNull { it.deviceName == playingDeviceName }
        if (playing != null &&
            playing.hasAudioInterface &&
            !isExcludedUsb(playing.vendorId, playing.productName, playing.manufacturerName, policy = policy)
        ) {
            return playing.deviceName
        }
        if (playingDeviceName.isNullOrBlank()) return null
        val others = usbDevices.filter { device ->
            device.hasAudioInterface &&
                !isExcludedUsb(device.vendorId, device.productName, device.manufacturerName, policy = policy)
        }
        return if (others.size == 1) others[0].deviceName else null
    }

    fun isGenericUacName(name: String?): Boolean {
        val text = normalize(name) ?: return true
        return GENERIC_UAC_PREFIXES.any { prefix -> text == prefix || text.startsWith("$prefix ") }
    }

    private fun tied(input: AudioInputIdentity, device: UsbIdentity): Boolean {
        return namesOverlap(input.productName, device.productName) ||
            namesOverlap(input.productName, device.manufacturerName) ||
            addressMatches(input.address, device.deviceName)
    }

    private fun matchesExcludedName(vararg names: String?): Boolean {
        return names.any { name ->
            val text = normalize(name) ?: return@any false
            EXCLUDED_PHRASES.any { phrase -> text.contains(phrase) }
        }
    }

    private fun matchesCaptureName(name: String?): Boolean {
        val text = normalize(name) ?: return false
        return CAPTURE_PHRASES.any { phrase -> text.contains(phrase) }
    }

    private fun namesOverlap(left: String?, right: String?): Boolean {
        val a = left?.trim().orEmpty()
        val b = right?.trim().orEmpty()
        if (a.length < 4 || b.length < 4) return false
        return a.contains(b, ignoreCase = true) || b.contains(a, ignoreCase = true)
    }

    private fun addressMatches(address: String?, deviceName: String?): Boolean {
        val addr = address?.trim().orEmpty()
        val path = deviceName?.trim().orEmpty()
        if (addr.length < 3 || path.length < 3) return false
        if (addr.equals(path, ignoreCase = true)) return true
        if (addr.contains(path, ignoreCase = true) || path.contains(addr, ignoreCase = true)) return true
        val tail = path.substringAfterLast("bus/usb/", path).trim('/')
        return tail.length >= 3 && tail != path && addr.contains(tail, ignoreCase = true)
    }

    private fun normalize(name: String?): String? {
        val text = name?.trim()?.lowercase().orEmpty()
        return text.ifEmpty { null }
    }
}
