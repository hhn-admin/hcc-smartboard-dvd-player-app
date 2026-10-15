package com.hcc.dvdplayer.logic

/**
 * Byte queue that holds a fixed lead of PCM so playback starts only after
 * [delayBytes] have arrived, then stays about that far behind the capture clock.
 */
class PcmDelayLine(private val delayBytes: Int) {
    init {
        require(delayBytes > 0) { "delayBytes must be positive" }
    }

    private var data = ByteArray(delayBytes * 2)
    private var head = 0
    private var count = 0

    fun queued(): Int = count

    fun primed(): Boolean = count >= delayBytes

    fun write(src: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        ensure(count + length)
        val tail = (head + count) % data.size
        val first = minOf(length, data.size - tail)
        System.arraycopy(src, offset, data, tail, first)
        if (first < length) {
            System.arraycopy(src, offset + first, data, 0, length - first)
        }
        count += length
    }

    fun read(max: Int): ByteArray {
        val size = minOf(max, count).coerceAtLeast(0)
        if (size == 0) return ByteArray(0)
        val out = ByteArray(size)
        val first = minOf(size, data.size - head)
        System.arraycopy(data, head, out, 0, first)
        if (first < size) {
            System.arraycopy(data, 0, out, first, size - first)
        }
        head = (head + size) % data.size
        count -= size
        if (count == 0) head = 0
        return out
    }

    fun trimTo(target: Int) {
        val drop = count - target
        if (drop <= 0) return
        head = (head + drop) % data.size
        count -= drop
        if (count == 0) head = 0
    }

    private fun ensure(needed: Int) {
        if (needed <= data.size) return
        var capacity = data.size
        while (capacity < needed) capacity *= 2
        val grown = ByteArray(capacity)
        val first = minOf(count, data.size - head)
        System.arraycopy(data, head, grown, 0, first)
        if (first < count) {
            System.arraycopy(data, 0, grown, first, count - first)
        }
        data = grown
        head = 0
    }
}
