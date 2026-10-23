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
        val granted = setOf(RuntimePermissions.CAMERA, RuntimePermissions.RECORD_AUDIO)
        assertTrue(auto(capture, granted))
        assertTrue(auto(capture, emptySet()))
        assertFalse(auto(emptyList(), granted))
        assertFalse(auto(listOf(usb("a", true), usb("b", true)), granted))
        assertFalse(auto(capture, granted, returnedFromPlayer = true))
        assertFalse(auto(capture, granted, openInProgress = true))
        assertFalse(auto(listOf(usb("maybe", captureCard = false)), granted))
        assertFalse(auto(listOf(camera("0")), granted))
    }

    @Test
    fun camera2WaitsForCameraAndMicrophone() {
        val camera = listOf(camera("0", captureCard = true))
        assertFalse(auto(camera, emptySet()))
        assertFalse(auto(camera, setOf(RuntimePermissions.CAMERA)))
        assertFalse(auto(camera, setOf(RuntimePermissions.RECORD_AUDIO)))
        assertTrue(auto(camera, setOf(RuntimePermissions.CAMERA, RuntimePermissions.RECORD_AUDIO)))
    }

    private fun auto(
        sources: List<ListedSource>,
        granted: Set<String>,
        returnedFromPlayer: Boolean = false,
        openInProgress: Boolean = false,
    ): Boolean {
        return PickerPrompt.shouldAutoStart(
            sources = sources,
            grantedPermissions = granted,
            returnedFromPlayer = returnedFromPlayer,
            openInProgress = openInProgress,
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

    private fun camera(id: String, captureCard: Boolean = false): ListedSource {
        return ListedSource(
            kind = ListedSource.Kind.CAMERA2_EXTERNAL,
            title = "External camera $id",
            subtitle = "System camera",
            usbDeviceName = null,
            cameraId = id,
            audioHint = null,
            captureCard = captureCard,
        )
    }
}
