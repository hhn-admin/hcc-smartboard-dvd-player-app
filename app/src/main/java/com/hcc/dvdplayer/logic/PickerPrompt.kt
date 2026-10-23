package com.hcc.dvdplayer.logic

import com.hcc.dvdplayer.source.ListedSource

/**
 * Which row to point at, and when a single capture card should start on its own.
 * The SMART webcam is not a source. Back from playback stays on the list until
 * the capture card disappears.
 */
object PickerPrompt {
    fun pointedSource(sources: List<ListedSource>): ListedSource? {
        val captures = sources.filter { it.captureCard }
        if (captures.size == 1) return captures.first()
        if (captures.isNotEmpty()) return null
        val usb = sources.filter { it.kind == ListedSource.Kind.USB_UVC }
        return if (usb.size == 1) usb.first() else null
    }

    /**
     * A USB capture card starts on its own with no runtime permission: libuvc
     * and the direct UAC reader use the USB device grant. A Camera2 source
     * still waits until camera and microphone are granted.
     */
    fun shouldAutoStart(
        sources: List<ListedSource>,
        grantedPermissions: Set<String>,
        returnedFromPlayer: Boolean,
        openInProgress: Boolean,
    ): Boolean {
        if (sources.size != 1 || !sources.first().captureCard) return false
        if (returnedFromPlayer || openInProgress) return false
        return RuntimePermissions.requiredFor(sources.first()).all { it in grantedPermissions }
    }
}
