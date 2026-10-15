package com.captureboard.hdmiin.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbVideoFilterTest {
    @Test
    fun matchesVideoClassDeviceOrInterface() {
        assertTrue(UsbVideoFilter.matches(UsbVideoFilter.CLASS_VIDEO, 0, emptyList()))
        assertTrue(
            UsbVideoFilter.matches(
                deviceClass = 0,
                deviceSubclass = 0,
                interfaces = listOf(UsbVideoFilter.InterfaceSnapshot(UsbVideoFilter.CLASS_VIDEO, 1)),
            ),
        )
    }

    @Test
    fun matchesCompositeMiscDevicesUsedByCaptureDongles() {
        assertTrue(UsbVideoFilter.matches(UsbVideoFilter.CLASS_MISC, UsbVideoFilter.SUBCLASS_MISC_COMMON, emptyList()))
        assertTrue(
            UsbVideoFilter.matches(
                deviceClass = 0,
                deviceSubclass = 0,
                interfaces = listOf(
                    UsbVideoFilter.InterfaceSnapshot(1, 1),
                    UsbVideoFilter.InterfaceSnapshot(UsbVideoFilter.CLASS_MISC, UsbVideoFilter.SUBCLASS_MISC_COMMON),
                ),
            ),
        )
    }

    @Test
    fun ignoresAudioOnlyAndHidDevices() {
        assertFalse(UsbVideoFilter.matches(1, 1, listOf(UsbVideoFilter.InterfaceSnapshot(1, 2))))
        assertFalse(UsbVideoFilter.matches(0, 0, listOf(UsbVideoFilter.InterfaceSnapshot(3, 0))))
    }
}
