package com.hcc.dvdplayer.logic

/**
 * A capture card with no HDMI signal usually delivers a black frame, or no
 * frame the copier can read. A failed sample is not treated as black: the
 * watch simply waits for the next one.
 */
object BlankFrame {
    const val DARK_CHANNEL = 16
    private const val DARK_FRACTION = 0.90

    fun isMostlyBlack(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return false
        var dark = 0
        for (pixel in pixels) {
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            if (maxOf(red, green, blue) <= DARK_CHANNEL) dark++
        }
        return dark >= pixels.size * DARK_FRACTION
    }
}

/**
 * Shows the no-signal line only after the preview has been open for
 * [showAfterMs] and the successful samples in that stretch were black.
 */
class BlankWatch(private val showAfterMs: Long = 4_000L) {
    private var openedAt: Long? = null
    private var blankSince: Long? = null
    var visible: Boolean = false
        private set

    fun onOpened(nowMs: Long) {
        openedAt = nowMs
        blankSince = null
        visible = false
    }

    /**
     * @param blank `true`/`false` from a successful sample, or `null` when the
     * sample failed and must not move the clock.
     */
    fun onSample(nowMs: Long, blank: Boolean?, frozen: Boolean) {
        val opened = openedAt ?: return
        if (frozen) return
        if (blank == null) return
        if (!blank) {
            blankSince = null
            visible = false
            return
        }
        val started = blankSince ?: nowMs.also { blankSince = it }
        visible = nowMs - opened >= showAfterMs && nowMs - started >= showAfterMs
    }

    fun onClosed() {
        openedAt = null
        blankSince = null
        visible = false
    }
}
