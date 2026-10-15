package com.captureboard.hdmiin.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFormatPlannerTest {
    @Test
    fun prefersOutputRateWhenTheInputAllowsIt() {
        assertEquals(44_100, AudioFormatPlanner.chooseRate(intArrayOf(48_000, 44_100), 44_100))
        assertEquals(48_000, AudioFormatPlanner.chooseRate(intArrayOf(48_000), 44_100))
        assertEquals(44_100, AudioFormatPlanner.chooseRate(intArrayOf(), 44_100))
        assertEquals(48_000, AudioFormatPlanner.chooseRate(intArrayOf(), null))
    }

    @Test
    fun prefersStereoUnlessTheDeviceIsMonoOnly() {
        assertEquals(2, AudioFormatPlanner.chooseChannels(intArrayOf()))
        assertEquals(2, AudioFormatPlanner.chooseChannels(intArrayOf(1, 2)))
        assertEquals(1, AudioFormatPlanner.chooseChannels(intArrayOf(1)))
    }

    @Test
    fun delayBytesMatchTheSyncHold() {
        val plan = AudioFormatPlanner.plan(
            reportedRates = intArrayOf(48_000),
            reportedChannels = intArrayOf(2),
            outputPreferredRate = 48_000,
            delayMs = 80,
        )
        assertEquals(48_000, plan.sampleRate)
        assertEquals(2, plan.channelCount)
        assertEquals(4, plan.frameBytes)
        assertEquals(48_000 * 4 * 80 / 1000, plan.delayBytes)
    }

    @Test
    fun rateAttemptsStayInsideTheReportedSet() {
        assertEquals(listOf(48_000), AudioFormatPlanner.rateAttempts(intArrayOf(48_000), 44_100))
    }
}
