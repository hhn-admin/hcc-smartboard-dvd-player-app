package com.hcc.dvdplayer.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UacPlaybackTest {
    @Test
    fun treatsAReported96kMonoInputAsTheCaptureStickLie() {
        assertTrue(UacPlayback.halReportsMono96k(intArrayOf(96_000), intArrayOf(1)))
    }

    @Test
    fun leavesOtherReportedShapesAlone() {
        assertFalse(UacPlayback.halReportsMono96k(intArrayOf(48_000), intArrayOf(2)))
        assertFalse(UacPlayback.halReportsMono96k(intArrayOf(96_000, 48_000), intArrayOf(1)))
        assertFalse(UacPlayback.halReportsMono96k(intArrayOf(96_000), intArrayOf(1, 2)))
        assertFalse(UacPlayback.halReportsMono96k(intArrayOf(), intArrayOf()))
        assertFalse(UacPlayback.halReportsMono96k(intArrayOf(96_000), intArrayOf()))
    }
}
