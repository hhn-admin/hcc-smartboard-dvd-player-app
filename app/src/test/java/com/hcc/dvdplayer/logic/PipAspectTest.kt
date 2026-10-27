package com.hcc.dvdplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipAspectTest {
    @Test
    fun keepsANormalFrameAndReducesIt() {
        assertEquals(16 to 9, PipAspect.rational(1920, 1080))
        assertEquals(4 to 3, PipAspect.rational(640, 480))
        assertEquals(3 to 2, PipAspect.rational(720, 480))
    }

    @Test
    fun clampsExtremeRatiosIntoThePipRange() {
        assertEquals(PipAspect.MAX_NUMERATOR to PipAspect.MAX_DENOMINATOR, PipAspect.rational(4000, 100))
        assertEquals(PipAspect.MIN_NUMERATOR to PipAspect.MIN_DENOMINATOR, PipAspect.rational(100, 4000))
        assertEquals(16 to 9, PipAspect.rational(0, 0))
    }

    @Test
    fun buttonStaysOffWhenTheFeatureOrTheSettingIsOff() {
        assertTrue(PipEntry.buttonVisible(enablePip = true, featurePresent = true))
        assertFalse(PipEntry.buttonVisible(enablePip = false, featurePresent = true))
        assertFalse(PipEntry.buttonVisible(enablePip = true, featurePresent = false))
    }

    @Test
    fun autoEnterOnlyWhileAPictureIsUpAndTheBoardAllowsIt() {
        assertTrue(
            PipEntry.autoEnter(
                enablePip = true,
                autoOnLeave = true,
                featurePresent = true,
                pictureStarted = true,
                finishing = false,
                inPip = false,
            ),
        )
        assertFalse(
            PipEntry.autoEnter(
                enablePip = true,
                autoOnLeave = false,
                featurePresent = true,
                pictureStarted = true,
                finishing = false,
                inPip = false,
            ),
        )
        assertFalse(
            PipEntry.autoEnter(
                enablePip = true,
                autoOnLeave = true,
                featurePresent = true,
                pictureStarted = false,
                finishing = false,
                inPip = false,
            ),
        )
        assertFalse(
            PipEntry.autoEnter(
                enablePip = true,
                autoOnLeave = true,
                featurePresent = true,
                pictureStarted = true,
                finishing = true,
                inPip = false,
            ),
        )
        assertFalse(
            PipEntry.autoEnter(
                enablePip = true,
                autoOnLeave = true,
                featurePresent = true,
                pictureStarted = true,
                finishing = false,
                inPip = true,
            ),
        )
    }
}
