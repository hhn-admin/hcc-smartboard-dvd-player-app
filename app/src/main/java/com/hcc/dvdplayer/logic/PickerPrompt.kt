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

    fun shouldAutoStart(
        sources: List<ListedSource>,
        permissionsGranted: Boolean,
        returnedFromPlayer: Boolean,
        openInProgress: Boolean,
    ): Boolean {
        if (sources.size != 1 || !sources.first().captureCard) return false
        return permissionsGranted && !returnedFromPlayer && !openInProgress
    }
}
