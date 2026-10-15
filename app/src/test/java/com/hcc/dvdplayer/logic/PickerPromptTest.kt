package com.hcc.dvdplayer.logic

import com.hcc.dvdplayer.source.ListedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerPromptTest {
    @Test
    fun pointsAtTheOnlyUsbSourceEvenWhenACameraIsListed() {
        val usb = usb("usb-1")
        val camera = camera("0")
        assertEquals(usb, PickerPrompt.pointedSource(listOf(usb, camera)))
    }

    @Test
    fun pointsAtTheOnlySourceWhenItIsNotUsb() {
        val camera = camera("0")
        assertEquals(camera, PickerPrompt.pointedSource(listOf(camera)))
    }

    @Test
    fun doesNotPointWhenSeveralUsbSourcesAreListed() {
        assertNull(PickerPrompt.pointedSource(listOf(usb("a"), usb("b"))))
    }

    @Test
    fun autoStartsOnlyASingleGrantedSource() {
        assertTrue(PickerPrompt.shouldAutoStart(1, permissionsGranted = true, returnedFromPlayer = false, openInProgress = false))
        assertFalse(PickerPrompt.shouldAutoStart(0, true, false, false))
        assertFalse(PickerPrompt.shouldAutoStart(2, true, false, false))
        assertFalse(PickerPrompt.shouldAutoStart(1, false, false, false))
        assertFalse(PickerPrompt.shouldAutoStart(1, true, true, false))
        assertFalse(PickerPrompt.shouldAutoStart(1, true, false, true))
    }

    private fun usb(name: String): ListedSource {
        return ListedSource(
            kind = ListedSource.Kind.USB_UVC,
            title = "DVD capture card",
            subtitle = "USB source",
            usbDeviceName = name,
            cameraId = null,
            audioHint = null,
        )
    }

    private fun camera(id: String): ListedSource {
        return ListedSource(
            kind = ListedSource.Kind.CAMERA2_EXTERNAL,
            title = "External camera $id",
            subtitle = "System camera",
            usbDeviceName = null,
            cameraId = id,
            audioHint = null,
        )
    }
}
