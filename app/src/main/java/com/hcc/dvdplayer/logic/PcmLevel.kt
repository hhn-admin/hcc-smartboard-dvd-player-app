package com.hcc.dvdplayer.logic

/**
 * Peak of a 16-bit little-endian PCM buffer, as 0 (silence) to 1 (full scale).
 * The meter uses this so a teacher can see that sound is actually arriving.
 */
object PcmLevel {
    fun peakUnit(pcm: ByteArray, length: Int): Float {
        if (length < 2 || pcm.isEmpty()) return 0f
        val limit = minOf(length, pcm.size) - (minOf(length, pcm.size) % 2)
        var peak = 0
        var index = 0
        while (index < limit) {
            val sample = (pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)
            val magnitude = kotlin.math.abs(sample.toShort().toInt())
            if (magnitude > peak) peak = magnitude
            index += 2
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }
}
