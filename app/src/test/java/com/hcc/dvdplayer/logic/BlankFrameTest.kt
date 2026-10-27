package com.hcc.dvdplayer.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlankFrameTest {
    @Test
    fun blackAndNearBlackAreBlank() {
        assertTrue(BlankFrame.isMostlyBlack(IntArray(100)))
        assertTrue(BlankFrame.isMostlyBlack(IntArray(10) { 0xFF101010.toInt() }))
        assertFalse(BlankFrame.isMostlyBlack(IntArray(0)))
    }

    @Test
    fun aBrightPixelKeepsTheFrameLive() {
        val pixels = intArrayOf(0xFF101010.toInt(), 0xFF111111.toInt())
        assertFalse(BlankFrame.isMostlyBlack(pixels))
    }

    @Test
    fun aFewBrightPixelsStillCountAsBlank() {
        val pixels = IntArray(100) { 0xFF000000.toInt() }
        repeat(10) { pixels[it] = 0xFFFFFFFF.toInt() }
        assertTrue(BlankFrame.isMostlyBlack(pixels))
        pixels[10] = 0xFFFFFFFF.toInt()
        assertFalse(BlankFrame.isMostlyBlack(pixels))
    }

    @Test
    fun noSignalWaitsForSustainedBlankSamples() {
        val watch = BlankWatch(showAfterMs = 4_000)
        watch.onOpened(0)
        watch.onSample(0, blank = true, frozen = false)
        watch.onSample(3_999, blank = true, frozen = false)
        assertFalse(watch.visible)
        watch.onSample(4_000, blank = true, frozen = false)
        assertTrue(watch.visible)
    }

    @Test
    fun aFailedSampleDoesNotCountAsBlankOrClearIt() {
        val watch = BlankWatch(showAfterMs = 4_000)
        watch.onOpened(0)
        watch.onSample(1_000, blank = null, frozen = false)
        watch.onSample(4_000, blank = null, frozen = false)
        assertFalse(watch.visible)
        watch.onSample(0, blank = true, frozen = false)
        watch.onSample(2_000, blank = null, frozen = false)
        assertFalse(watch.visible)
        watch.onSample(4_000, blank = true, frozen = false)
        assertTrue(watch.visible)
    }

    @Test
    fun aLiveSampleAndFreezeDoNotInventASignalChange() {
        val watch = BlankWatch(showAfterMs = 1_000)
        watch.onOpened(0)
        watch.onSample(0, blank = true, frozen = false)
        watch.onSample(1_000, blank = true, frozen = false)
        assertTrue(watch.visible)
        watch.onSample(1_100, blank = false, frozen = false)
        assertFalse(watch.visible)
        watch.onSample(1_200, blank = true, frozen = false)
        watch.onSample(2_200, blank = false, frozen = true)
        assertFalse(watch.visible)
    }
}
