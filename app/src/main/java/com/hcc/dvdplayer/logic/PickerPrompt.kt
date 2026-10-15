package com.hcc.dvdplayer.logic

import com.hcc.dvdplayer.source.ListedSource

/**
 * Which row to point at, and when a single source should start on its own.
 * Playback stays put after Back until the list is no longer a single source.
 */
object PickerPrompt {
    fun pointedSource(sources: List<ListedSource>): ListedSource? {
        val usb = sources.filter { it.kind == ListedSource.Kind.USB_UVC }
        return when {
            usb.size == 1 -> usb.first()
            sources.size == 1 -> sources.first()
            else -> null
        }
    }

    fun shouldAutoStart(
        sourceCount: Int,
        permissionsGranted: Boolean,
        returnedFromPlayer: Boolean,
        openInProgress: Boolean,
    ): Boolean {
        return sourceCount == 1 && permissionsGranted && !returnedFromPlayer && !openInProgress
    }
}
