package com.hcc.dvdplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedSettingsTest {
    @Test
    fun defaultsWhenTheMdmSendsNothing() {
        val settings = ManagedSettings.parse(RestrictionInput(emptySet()), localLock = false)
        assertTrue(settings.autoStart)
        assertFalse(settings.zoomToFillDefault)
        assertFalse(settings.lockControls)
        assertFalse(settings.lockManaged)
        assertTrue(settings.showAudioMeter)
        assertTrue(settings.enableSnapshot)
        assertTrue(settings.enableFreeze)
        assertEquals(4, settings.controlsAutohideSeconds)
        assertTrue(settings.excludeSmartDevices)
        assertTrue(settings.extraExcludedVids.isEmpty())
        assertTrue(settings.exclusionPolicy.excludeSmartDevices)
        assertTrue(settings.enablePip)
        assertTrue(settings.autoPipOnLeave)
    }

    @Test
    fun presentValuesOverrideDefaults() {
        val settings = ManagedSettings.parse(
            RestrictionInput(
                present = setOf(
                    ManagedSettings.AUTO_START,
                    ManagedSettings.ZOOM_TO_FILL_DEFAULT,
                    ManagedSettings.SHOW_AUDIO_METER,
                    ManagedSettings.ENABLE_SNAPSHOT,
                    ManagedSettings.ENABLE_FREEZE,
                    ManagedSettings.CONTROLS_AUTOHIDE_SECONDS,
                    ManagedSettings.EXCLUDE_SMART_DEVICES,
                    ManagedSettings.ENABLE_PIP,
                    ManagedSettings.AUTO_PIP_ON_LEAVE,
                ),
                bools = mapOf(
                    ManagedSettings.AUTO_START to false,
                    ManagedSettings.ZOOM_TO_FILL_DEFAULT to true,
                    ManagedSettings.SHOW_AUDIO_METER to false,
                    ManagedSettings.ENABLE_SNAPSHOT to false,
                    ManagedSettings.ENABLE_FREEZE to false,
                    ManagedSettings.EXCLUDE_SMART_DEVICES to false,
                    ManagedSettings.ENABLE_PIP to false,
                    ManagedSettings.AUTO_PIP_ON_LEAVE to false,
                ),
                ints = mapOf(ManagedSettings.CONTROLS_AUTOHIDE_SECONDS to 9),
            ),
            localLock = true,
        )
        assertFalse(settings.autoStart)
        assertTrue(settings.zoomToFillDefault)
        assertFalse(settings.showAudioMeter)
        assertFalse(settings.enableSnapshot)
        assertFalse(settings.enableFreeze)
        assertFalse(settings.excludeSmartDevices)
        assertFalse(settings.enablePip)
        assertFalse(settings.autoPipOnLeave)
        assertEquals(9, settings.controlsAutohideSeconds)
        assertTrue(settings.lockControls)
        assertFalse(settings.lockManaged)
    }

    @Test
    fun managedLockOverridesTheLocalHold() {
        val locked = ManagedSettings.parse(
            RestrictionInput(
                present = setOf(ManagedSettings.LOCK_CONTROLS),
                bools = mapOf(ManagedSettings.LOCK_CONTROLS to true),
            ),
            localLock = false,
        )
        assertTrue(locked.lockManaged)
        assertTrue(locked.lockControls)
        val unlocked = ManagedSettings.parse(
            RestrictionInput(
                present = setOf(ManagedSettings.LOCK_CONTROLS),
                bools = mapOf(ManagedSettings.LOCK_CONTROLS to false),
            ),
            localLock = true,
        )
        assertTrue(unlocked.lockManaged)
        assertFalse(unlocked.lockControls)
    }

    @Test
    fun autohideIsClampedAndAcceptsAString() {
        val low = ManagedSettings.parse(
            RestrictionInput(
                present = setOf(ManagedSettings.CONTROLS_AUTOHIDE_SECONDS),
                ints = mapOf(ManagedSettings.CONTROLS_AUTOHIDE_SECONDS to 0),
            ),
            localLock = false,
        )
        val high = ManagedSettings.parse(
            RestrictionInput(
                present = setOf(ManagedSettings.CONTROLS_AUTOHIDE_SECONDS),
                strings = mapOf(ManagedSettings.CONTROLS_AUTOHIDE_SECONDS to "99"),
            ),
            localLock = false,
        )
        assertEquals(1, low.controlsAutohideSeconds)
        assertEquals(60, high.controlsAutohideSeconds)
    }

    @Test
    fun parsesCommaSeparatedVendorIds() {
        assertEquals(emptySet<Int>(), ManagedSettings.parseVendorIds("  "))
        assertEquals(
            setOf(0x1234, 0x534D, 0x0B8C),
            ManagedSettings.parseVendorIds("0x1234, 534D, zz, 0X0b8c"),
        )
    }
}
