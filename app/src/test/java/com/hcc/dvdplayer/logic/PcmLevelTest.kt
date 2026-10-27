package com.hcc.dvdplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmLevelTest {
    @Test
    fun silenceIsZero() {
        assertEquals(0f, PcmLevel.peakUnit(ByteArray(8), 8), 0.0001f)
        assertEquals(0f, PcmLevel.peakUnit(ByteArray(0), 0), 0.0001f)
        assertEquals(0f, PcmLevel.peakUnit(byteArrayOf(0x7F), 1), 0.0001f)
    }

    @Test
    fun fullScaleNegativeIsOne() {
        val pcm = byteArrayOf(0x00, 0x80.toByte())
        assertEquals(1f, PcmLevel.peakUnit(pcm, pcm.size), 0.0001f)
    }

    @Test
    fun usesTheLouderSampleAndIgnoresATrailingByte() {
        val quiet = le(1000)
        val loud = le(16384)
        val pcm = quiet + loud + byteArrayOf(0x7F)
        assertEquals(16384f / 32768f, PcmLevel.peakUnit(pcm, pcm.size), 0.0001f)
    }

    private fun le(sample: Int): ByteArray {
        return byteArrayOf((sample and 0xFF).toByte(), ((sample shr 8) and 0xFF).toByte())
    }
}
