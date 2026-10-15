package com.captureboard.hdmiin.logic

/**
 * Plans the PCM format used to loop USB audio (UAC) back out as media audio.
 * Empty device-reported arrays mean "the HAL did not constrain the rate", which
 * on Android is treated as "any common rate is acceptable".
 */
object AudioFormatPlanner {
    /** UVC decode + render is usually a couple of frames behind the USB audio clock. */
    const val UVC_SYNC_DELAY_MS = 80

    /** Camera2 preview is a bit tighter; a shorter hold keeps speech from lagging. */
    const val CAMERA2_SYNC_DELAY_MS = 40

    private val RATE_FALLBACKS = intArrayOf(48_000, 44_100, 32_000, 16_000)

    data class Plan(
        val sampleRate: Int,
        val channelCount: Int,
        val frameBytes: Int,
        val delayBytes: Int,
    )

    fun plan(
        reportedRates: IntArray,
        reportedChannels: IntArray,
        outputPreferredRate: Int?,
        delayMs: Int,
    ): Plan {
        val rate = chooseRate(reportedRates, outputPreferredRate)
        val channels = chooseChannels(reportedChannels)
        val frameBytes = channels * 2
        val delayBytes = ((rate.toLong() * frameBytes * delayMs) / 1000L).toInt().coerceAtLeast(frameBytes)
        return Plan(rate, channels, frameBytes, delayBytes)
    }

    fun rateAttempts(reportedRates: IntArray, outputPreferredRate: Int?): List<Int> {
        val allowed = reportedRates.filter { it > 0 }.toSet()
        val ordered = mutableListOf<Int>()
        val preferred = chooseRate(reportedRates, outputPreferredRate)
        ordered += preferred
        if (outputPreferredRate != null && outputPreferredRate > 0) ordered += outputPreferredRate
        ordered += RATE_FALLBACKS.toList()
        ordered += allowed
        return ordered.distinct().filter { allowed.isEmpty() || it in allowed }
    }

    fun chooseRate(reportedRates: IntArray, outputPreferredRate: Int?): Int {
        val allowed = reportedRates.filter { it > 0 }
        if (allowed.isEmpty()) {
            return outputPreferredRate?.takeIf { it > 0 } ?: RATE_FALLBACKS[0]
        }
        if (outputPreferredRate != null && outputPreferredRate in allowed) return outputPreferredRate
        return RATE_FALLBACKS.firstOrNull { it in allowed }
            ?: allowed.minBy { kotlin.math.abs(it - RATE_FALLBACKS[0]) }
    }

    fun chooseChannels(reportedChannels: IntArray): Int {
        val allowed = reportedChannels.filter { it == 1 || it == 2 }
        if (allowed.isEmpty()) return 2
        return if (2 in allowed) 2 else 1
    }

    fun channelAttempts(reportedChannels: IntArray): List<Int> {
        val preferred = chooseChannels(reportedChannels)
        val allowed = reportedChannels.filter { it == 1 || it == 2 }.toSet()
        val ordered = listOf(preferred, 2, 1).distinct()
        return if (allowed.isEmpty()) ordered else ordered.filter { it in allowed }
    }
}
