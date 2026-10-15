package com.captureboard.hdmiin.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmDelayLineTest {
    @Test
    fun primesOnlyAfterTheDelayAndThenReturnsOldestBytes() {
        val line = PcmDelayLine(4)
        line.write(byteArrayOf(1, 2), 0, 2)
        assertFalse(line.primed())
        line.write(byteArrayOf(3, 4, 5), 0, 3)
        assertTrue(line.primed())
        assertEquals(5, line.queued())
        assertEquals(listOf<Byte>(1, 2, 3), line.read(3).toList())
        assertEquals(2, line.queued())
    }

    @Test
    fun wrapsAndTrimsBackToTheDelay() {
        val line = PcmDelayLine(4)
        line.write(ByteArray(10) { it.toByte() }, 0, 10)
        line.trimTo(4)
        assertEquals(4, line.queued())
        assertEquals(listOf<Byte>(6, 7, 8, 9), line.read(4).toList())
        assertEquals(0, line.queued())
    }
}
