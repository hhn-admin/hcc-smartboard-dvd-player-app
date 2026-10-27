package com.hcc.dvdplayer.logic

import com.hcc.dvdplayer.source.ListedSource

/**
 * The source that should start on its own.
 *
 * A remembered card wins when it is plugged in, even if another USB device is
 * listed. Otherwise the single eligible capture card starts, which is the
 * same rule as [PickerPrompt.shouldAutoStart]. Back to the list, a launch
 * already in progress, or `auto_start` off all stay on the picker.
 */
object LaunchChoice {
    fun choose(
        sources: List<ListedSource>,
        grantedPermissions: Set<String>,
        returnedFromPlayer: Boolean,
        openInProgress: Boolean,
        autoStart: Boolean,
        remembered: RememberedSource?,
    ): ListedSource? {
        if (!autoStart || returnedFromPlayer || openInProgress) return null
        val rememberedMatch = remembered?.let { memory ->
            sources.firstOrNull { source ->
                matches(source, memory) && permitted(source, grantedPermissions)
            }
        }
        if (rememberedMatch != null) return rememberedMatch
        if (!PickerPrompt.shouldAutoStart(
                sources = sources,
                grantedPermissions = grantedPermissions,
                returnedFromPlayer = false,
                openInProgress = false,
            )
        ) {
            return null
        }
        return sources.first()
    }

    fun matches(source: ListedSource, memory: RememberedSource): Boolean {
        if (source.kind != memory.kind) return false
        return when (source.kind) {
            ListedSource.Kind.USB_UVC ->
                memory.vendorId != 0 &&
                    source.vendorId == memory.vendorId &&
                    source.productId == memory.productId
            ListedSource.Kind.CAMERA2_EXTERNAL ->
                !memory.cameraId.isNullOrBlank() && source.cameraId == memory.cameraId
        }
    }

    private fun permitted(source: ListedSource, granted: Set<String>): Boolean {
        return RuntimePermissions.requiredFor(source).all { it in granted }
    }
}

/**
 * Last source that actually showed a picture. USB identity is vendor and
 * product, because the `/dev/bus/usb` path changes every time the card is
 * plugged in. Camera2 identity is the camera id.
 */
data class RememberedSource(
    val kind: ListedSource.Kind,
    val vendorId: Int = 0,
    val productId: Int = 0,
    val cameraId: String? = null,
)
