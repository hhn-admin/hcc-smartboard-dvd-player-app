package com.hcc.dvdplayer.logic

import com.hcc.dvdplayer.source.ListedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchChoiceTest {
    @Test
    fun rememberedUsbStartsEvenWhenAnotherSourceIsListed() {
        val card = usb("card", vendor = 0x534D, product = 0x2109, captureCard = true)
        val other = usb("other", vendor = 0x1B80, product = 0xE409, captureCard = true)
        val choice = LaunchChoice.choose(
            sources = listOf(other, card),
            grantedPermissions = emptySet(),
            returnedFromPlayer = false,
            openInProgress = false,
            autoStart = true,
            remembered = RememberedSource(ListedSource.Kind.USB_UVC, vendorId = 0x534D, productId = 0x2109),
        )
        assertEquals(card, choice)
    }

    @Test
    fun fallsBackToTheOnlyCaptureCardWhenNothingMatches() {
        val card = usb("card", vendor = 0x534D, product = 0x2109, captureCard = true)
        val choice = LaunchChoice.choose(
            sources = listOf(card),
            grantedPermissions = emptySet(),
            returnedFromPlayer = false,
            openInProgress = false,
            autoStart = true,
            remembered = RememberedSource(ListedSource.Kind.USB_UVC, vendorId = 0x1111, productId = 0x2222),
        )
        assertEquals(card, choice)
    }

    @Test
    fun staysOnThePickerAfterChangeSourceUntilThatCardIsGone() {
        val card = usb("card", vendor = 0x534D, product = 0x2109, captureCard = true)
        val remembered = RememberedSource(ListedSource.Kind.USB_UVC, vendorId = 0x534D, productId = 0x2109)
        assertNull(
            LaunchChoice.choose(
                sources = listOf(card),
                grantedPermissions = emptySet(),
                returnedFromPlayer = true,
                openInProgress = false,
                autoStart = true,
                remembered = remembered,
            ),
        )
        assertEquals(
            card,
            LaunchChoice.choose(
                sources = listOf(card),
                grantedPermissions = emptySet(),
                returnedFromPlayer = false,
                openInProgress = false,
                autoStart = true,
                remembered = remembered,
            ),
        )
    }

    @Test
    fun autoStartOffNeverOpens() {
        val card = usb("card", vendor = 0x534D, product = 0x2109, captureCard = true)
        assertNull(
            LaunchChoice.choose(
                sources = listOf(card),
                grantedPermissions = emptySet(),
                returnedFromPlayer = false,
                openInProgress = false,
                autoStart = false,
                remembered = RememberedSource(ListedSource.Kind.USB_UVC, 0x534D, 0x2109),
            ),
        )
    }

    @Test
    fun rememberedCamera2WaitsForCameraAndMicrophone() {
        val camera = camera("hdmi-0")
        val memory = RememberedSource(ListedSource.Kind.CAMERA2_EXTERNAL, cameraId = "hdmi-0")
        assertNull(chooseCamera(camera, emptySet(), memory))
        assertNull(chooseCamera(camera, setOf(RuntimePermissions.CAMERA), memory))
        assertEquals(
            camera,
            chooseCamera(camera, setOf(RuntimePermissions.CAMERA, RuntimePermissions.RECORD_AUDIO), memory),
        )
    }

    @Test
    fun aMissingVendorDoesNotCountAsTheRememberedCard() {
        val card = usb("card", vendor = 0, product = 0, captureCard = true)
        val memory = RememberedSource(ListedSource.Kind.USB_UVC, vendorId = 0, productId = 0)
        assertFalse(LaunchChoice.matches(card, memory))
        assertEquals(
            card,
            LaunchChoice.choose(
                sources = listOf(card),
                grantedPermissions = emptySet(),
                returnedFromPlayer = false,
                openInProgress = false,
                autoStart = true,
                remembered = memory,
            ),
        )
    }

    private fun chooseCamera(
        camera: ListedSource,
        granted: Set<String>,
        memory: RememberedSource,
    ): ListedSource? {
        return LaunchChoice.choose(
            sources = listOf(camera),
            grantedPermissions = granted,
            returnedFromPlayer = false,
            openInProgress = false,
            autoStart = true,
            remembered = memory,
        )
    }

    private fun usb(name: String, vendor: Int, product: Int, captureCard: Boolean): ListedSource {
        return ListedSource(
            kind = ListedSource.Kind.USB_UVC,
            title = name,
            subtitle = "USB source",
            usbDeviceName = name,
            cameraId = null,
            audioHint = null,
            captureCard = captureCard,
            vendorId = vendor,
            productId = product,
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
            captureCard = true,
        )
    }
}
