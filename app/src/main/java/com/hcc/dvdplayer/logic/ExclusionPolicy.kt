package com.hcc.dvdplayer.logic

/**
 * Which USB devices stay off the source list and off the audio loopback.
 * Managed configuration can turn off the SMART exclusion or add more vendor ids.
 * The default matches the board behavior: vendor 0x0B8C and the SMART names stay out.
 */
data class ExclusionPolicy(
    val excludeSmartDevices: Boolean = true,
    val extraVendorIds: Set<Int> = emptySet(),
) {
    companion object {
        val DEFAULT = ExclusionPolicy()
    }
}
