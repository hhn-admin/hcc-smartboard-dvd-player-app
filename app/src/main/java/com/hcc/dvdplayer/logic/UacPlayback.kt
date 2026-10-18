package com.hcc.dvdplayer.logic

/**
 * MacroSilicon-class HDMI sticks (the Audio Express AXHDCAP and the same
 * layout) advertise 16-bit mono at 96 kHz. The bytes on the wire are stereo
 * 16-bit at 48 kHz: both interpretations are 192000 bytes/s. Linux's MS2109
 * quirk plays them as stereo. Android's USB audio HAL often does not, and
 * then [AudioDeviceInfo] reports only 96000 Hz and 1 channel.
 *
 * This check uses that reported shape. It does not look at a vendor id.
 */
object UacPlayback {
    fun halReportsMono96k(rates: IntArray, channels: IntArray): Boolean {
        val rateList = rates.filter { it > 0 }
        val channelList = channels.filter { it > 0 }
        return rateList == listOf(96_000) && channelList == listOf(1)
    }
}
