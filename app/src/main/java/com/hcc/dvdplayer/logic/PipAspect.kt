package com.hcc.dvdplayer.logic

/**
 * Aspect ratio for [android.app.PictureInPictureParams]. Android rejects a
 * ratio outside about 1:2.39 to 2.39:1, so a very tall or very wide frame is
 * clamped and everything else is reduced (1920x1080 becomes 16:9).
 */
object PipAspect {
    const val MIN_NUMERATOR = 100
    const val MIN_DENOMINATOR = 239
    const val MAX_NUMERATOR = 239
    const val MAX_DENOMINATOR = 100

    fun rational(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 16 to 9
        val ratio = width.toDouble() / height.toDouble()
        val min = MIN_NUMERATOR.toDouble() / MIN_DENOMINATOR
        val max = MAX_NUMERATOR.toDouble() / MAX_DENOMINATOR
        if (ratio < min) return MIN_NUMERATOR to MIN_DENOMINATOR
        if (ratio > max) return MAX_NUMERATOR to MAX_DENOMINATOR
        return reduce(width, height)
    }

    private fun reduce(width: Int, height: Int): Pair<Int, Int> {
        val divisor = gcd(width, height)
        return width / divisor to height / divisor
    }

    private fun gcd(left: Int, right: Int): Int {
        var a = kotlin.math.abs(left)
        var b = kotlin.math.abs(right)
        while (b != 0) {
            val next = a % b
            a = b
            b = next
        }
        return a.coerceAtLeast(1)
    }
}

/**
 * When the floating window is offered, and when leaving the app should enter it.
 * The button stays available while controls are locked; that decision is not here.
 */
object PipEntry {
    fun buttonVisible(enablePip: Boolean, featurePresent: Boolean): Boolean {
        return enablePip && featurePresent
    }

    fun autoEnter(
        enablePip: Boolean,
        autoOnLeave: Boolean,
        featurePresent: Boolean,
        pictureStarted: Boolean,
        finishing: Boolean,
        inPip: Boolean,
    ): Boolean {
        return enablePip &&
            autoOnLeave &&
            featurePresent &&
            pictureStarted &&
            !finishing &&
            !inPip
    }
}
