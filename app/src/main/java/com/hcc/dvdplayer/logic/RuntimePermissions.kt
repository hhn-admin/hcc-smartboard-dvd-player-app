package com.hcc.dvdplayer.logic

import com.hcc.dvdplayer.source.ListedSource

/**
 * Runtime permissions the picker may request.
 *
 * libuvc opens the capture card through the USB host API, so the USB path does
 * not need [CAMERA]. The direct isochronous reader uses that same USB device
 * permission, so the USB path does not need [RECORD_AUDIO] either.
 * Camera2 and [android.media.AudioRecord] still need the permissions below,
 * and only those paths request them.
 */
object RuntimePermissions {
    const val CAMERA = "android.permission.CAMERA"
    const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"

    fun requiredFor(source: ListedSource): Set<String> {
        return when (source.kind) {
            ListedSource.Kind.USB_UVC -> emptySet()
            ListedSource.Kind.CAMERA2_EXTERNAL -> setOf(CAMERA, RECORD_AUDIO)
        }
    }
}
