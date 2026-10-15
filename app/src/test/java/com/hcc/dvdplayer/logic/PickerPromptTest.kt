package com.hcc.dvdplayer.logic

import com.hcc.dvdplayer.source.ListedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerPromptTest {
    @Test
    fun pointsAtTheOnlyCaptureCard() {
        val usb = usb("usb-1", captureCard = true)
        val ambiguous = usb("usb-2", captureCard = false)
        assertEquals(usb, PickerPrompt.pointedSource(listOf(usb, ambiguous)))
    }

    @Test
    fun pointsAtOneAmbiguousUsbSourceWithoutTreatingItAsACaptureCard() {
        val ambiguous = usb("usb-1", captureCard = false)
        assertEquals(ambiguous, PickerPrompt.pointedSource(listOf(ambiguous)))
        assertNull(PickerPrompt.pointedSource(listOf(camera("0"))))
    }

    @Test
    fun doesNotPointWhenSeveralUsbSourcesAreListed() {
        assertNull(PickerPrompt.pointedSource(listOf(usb("a"), usb("b"))))
    }

    @Test
    fun autoStartsOnlyASingleIdentifiedCaptureCard() {
        val capture = listOf(usb("card", captureCard = true))
        assertTrue(PickerPrompt.shouldAutoStart(capture, permissionsGranted = true, returnedFromPlayer = false, openInProgress = false))
        assertFalse(PickerPrompt.shouldAutoStart(emptyList(), true, false, false))
        assertFalse(PickerPrompt.shouldAutoStart(listOf(usb("a", true), usb("b", true)), true, false, false))
        assertFalse(PickerPrompt.shouldAutoStart(capture, false, false, false))
        assertFalse(PickerPrompt.shouldAutoStart(capture, true, true, false))
        assertFalse(PickerPrompt.shouldAutoStart(capture, true, false, true))
        assertFalse(
            PickerPrompt.shouldAutoStart(
                listOf(usb("maybe", captureCard = false)),
                permissionsGranted = true,
                returnedFromPlayer = false,
                openInProgress = false,
            ),
        )
        assertFalse(
            PickerPrompt.shouldAutoStart(
                listOf(camera("0")),
                permissionsGranted = true,
                returnedFromPlayer = false,
                openInProgress = false,
            ),
        )
    }

    private fun usb(name: String, captureCard: Boolean = false): ListedSource {
        return ListedSource(
            kind = ListedSource.Kind.USB_UVC,
            title = "DVD capture card",
            subtitle = "USB source",
            usbDeviceName = name,
            cameraId = null,
            audioHint = null,
            captureCard = captureCard,
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
            captureCard = false,
        )
    }
}
