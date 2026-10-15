package com.hcc.dvdplayer.logic

/**
 * SMART boards keep a SMART Ultra HD Camera 120 (SWC-120UHD) plugged in.
 * That webcam is USB vendor 0x0B8C. It must never be listed, auto-started, or
 * used as the audio loopback. HDMI capture dongles are chosen by name.
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

    data class UsbIdentity(
        val vendorId: Int,
        val deviceName: String?,
        val productName: String?,
        val manufacturerName: String?,
    )

    data class AudioInputIdentity(
        val productName: String?,
        val address: String?,
    )

    fun isExcludedUsb(vendorId: Int, vararg names: String?): Boolean {
        if (vendorId == SMART_VENDOR_ID) return true
        return names.any { matchesExcludedName(it) }
    }

    fun looksLikeCaptureCard(vararg names: String?): Boolean {
        if (names.any { matchesExcludedName(it) }) return false
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
    ): List<T> {
        val allowed = devices.filter { device ->
            !isExcludedUsb(vendorId(device), productName(device), manufacturerName(device))
        }
        val captures = allowed.filter { device ->
            looksLikeCaptureCard(productName(device), manufacturerName(device))
        }
        return if (captures.isNotEmpty()) captures else allowed
    }

    /**
     * Camera2 is shown only when no USB source was kept and the camera name
     * itself looks like a capture card. The SMART webcam and built-in cameras
     * do not.
     */
    fun allowCamera2(labels: List<String?>, listedUsbCount: Int): Boolean {
        if (listedUsbCount > 0) return false
        if (labels.any { matchesExcludedName(it) }) return false
        return looksLikeCaptureCard(*labels.toTypedArray())
    }

    fun selectAudioInputIndexes(
        inputs: List<AudioInputIdentity>,
        productHint: String?,
        usbDevices: List<UsbIdentity>,
    ): List<Int> {
        val allowed = inputs.indices.filter { index ->
            !isBlockedAudio(inputs[index], usbDevices, productHint)
        }
        val hint = productHint?.trim().orEmpty()
        if (hint.isEmpty()) return allowed
        val matched = allowed.filter { index ->
            inputs[index].productName?.contains(hint, ignoreCase = true) == true
        }
        return if (matched.isEmpty()) allowed else matched
    }

    fun isBlockedAudio(
        input: AudioInputIdentity,
        usbDevices: List<UsbIdentity>,
        productHint: String? = null,
    ): Boolean {
        if (matchesExcludedName(input.productName)) return true
        val blockedDevices = usbDevices.filter { device ->
            device.vendorId == SMART_VENDOR_ID || matchesExcludedName(device.productName, device.manufacturerName)
        }
        if (blockedDevices.any { device -> tied(input, device) }) return true
        if (usbDevices.none { it.vendorId == SMART_VENDOR_ID }) return false
        val hint = productHint?.trim().orEmpty()
        if (hint.length >= 4 && input.productName?.contains(hint, ignoreCase = true) == true) return false
        return !usbDevices.any { device ->
            device.vendorId != SMART_VENDOR_ID &&
                !matchesExcludedName(device.productName, device.manufacturerName) &&
                tied(input, device)
        }
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
