package com.hcc.dvdplayer.logic

import com.hcc.dvdplayer.logic.CaptureSourceFilter.AudioInputIdentity
import com.hcc.dvdplayer.logic.CaptureSourceFilter.UsbIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSourceFilterTest {
    @Test
    fun hardExcludesSmartVendorEvenWhenTheNameLooksLikeCapture() {
        assertTrue(
            CaptureSourceFilter.isExcludedUsb(
                CaptureSourceFilter.SMART_VENDOR_ID,
                "HDMI Capture",
                "MacroSilicon",
            ),
        )
    }

    @Test
    fun excludesSmartWebcamNames() {
        assertTrue(CaptureSourceFilter.isExcludedUsb(0x1234, "SMART Ultra HD Camera", "SMART Technologies"))
        assertTrue(CaptureSourceFilter.isExcludedUsb(0x1234, "SWC-120UHD", null))
        assertTrue(CaptureSourceFilter.isExcludedUsb(0x1234, "SWC120 Camera", null))
        assertTrue(CaptureSourceFilter.isExcludedUsb(0x1234, "Integrated Webcam", null))
        assertTrue(CaptureSourceFilter.isExcludedUsb(0x1234, "HD Webcam", null))
        assertFalse(CaptureSourceFilter.isExcludedUsb(0x345F, "USB3.0 HD Video Capture", null))
    }

    @Test
    fun recognizesCaptureDongleNames() {
        assertTrue(CaptureSourceFilter.looksLikeCaptureCard("USB3.0 HD Video Capture", null))
        assertTrue(CaptureSourceFilter.looksLikeCaptureCard("MS2130", "MacroSilicon"))
        assertTrue(CaptureSourceFilter.looksLikeCaptureCard("MS2109 HDMI", null))
        assertTrue(CaptureSourceFilter.looksLikeCaptureCard("Elgato Cam Link", null))
        assertTrue(CaptureSourceFilter.looksLikeCaptureCard("ezcap Game Link", null))
        assertTrue(CaptureSourceFilter.looksLikeCaptureCard("AVG HDMI Grabber", null))
        assertTrue(CaptureSourceFilter.looksLikeCaptureCard(null, "Mack"))
        assertFalse(CaptureSourceFilter.looksLikeCaptureCard("SMART Ultra HD Camera", "SMART Technologies"))
        assertFalse(CaptureSourceFilter.looksLikeCaptureCard("USB Video", null))
    }

    @Test
    fun prefersNamedCaptureCardsAndDropsTheSmartWebcam() {
        val devices = listOf(
            usb(CaptureSourceFilter.SMART_VENDOR_ID, "SMART Ultra HD Camera", "SMART Technologies"),
            usb(0x345F, "USB3.0 HD Video Capture", "MacroSilicon"),
            usb(0x046D, "HD Webcam", "Logitech"),
        )
        val chosen = CaptureSourceFilter.selectUsbVideo(devices, { it.vendorId }, { it.productName }, { it.manufacturerName })
        assertEquals(listOf("USB3.0 HD Video Capture"), chosen.map { it.productName })
    }

    @Test
    fun keepsAmbiguousUsbVideoWhenNothingLooksLikeACaptureCard() {
        val devices = listOf(
            usb(CaptureSourceFilter.SMART_VENDOR_ID, "SWC-120UHD", "SMART Technologies"),
            usb(0x1B3F, "USB Video", null),
        )
        val chosen = CaptureSourceFilter.selectUsbVideo(devices, { it.vendorId }, { it.productName }, { it.manufacturerName })
        assertEquals(listOf("USB Video"), chosen.map { it.productName })
    }

    @Test
    fun hidesCamera2WhenAUsbSourceIsAlreadyListed() {
        assertFalse(CaptureSourceFilter.allowCamera2(listOf("HDMI Capture"), listedUsbCount = 1))
    }

    @Test
    fun showsCamera2OnlyWhenItsNameIsACaptureCard() {
        assertTrue(CaptureSourceFilter.allowCamera2(listOf("0", "MS2130 HDMI"), listedUsbCount = 0))
        assertFalse(CaptureSourceFilter.allowCamera2(listOf("0"), listedUsbCount = 0))
        assertFalse(CaptureSourceFilter.allowCamera2(listOf("SMART Ultra HD Camera"), listedUsbCount = 0))
        assertFalse(CaptureSourceFilter.allowCamera2(listOf("Integrated Camera"), listedUsbCount = 0))
    }

    @Test
    fun neverSelectsSmartVendorAudio() {
        val smart = UsbIdentity(
            vendorId = CaptureSourceFilter.SMART_VENDOR_ID,
            deviceName = "/dev/bus/usb/001/003",
            productName = "SMART Ultra HD Camera",
            manufacturerName = "SMART Technologies",
        )
        val capture = UsbIdentity(
            vendorId = 0x345F,
            deviceName = "/dev/bus/usb/001/005",
            productName = "USB3.0 HD Video Capture",
            manufacturerName = "MacroSilicon",
        )
        val inputs = listOf(
            AudioInputIdentity("SMART Ultra HD Camera", "card=1;device=0"),
            AudioInputIdentity("USB3.0 HD Video Capture", "/dev/bus/usb/001/005"),
            AudioInputIdentity(null, "001/003"),
            AudioInputIdentity("Board Mic Array", null),
        )
        val indexes = CaptureSourceFilter.selectAudioInputIndexes(inputs, "USB3.0 HD Video Capture", listOf(smart, capture))
        assertEquals(listOf(1), indexes)
    }

    @Test
    fun blocksAudioTiedToTheSmartDeviceByAddressOrName() {
        val smart = UsbIdentity(
            vendorId = CaptureSourceFilter.SMART_VENDOR_ID,
            deviceName = "/dev/bus/usb/002/004",
            productName = "SWC-120UHD",
            manufacturerName = "SMART Technologies",
        )
        assertTrue(
            CaptureSourceFilter.isBlockedAudio(
                AudioInputIdentity("Digital Mic", "/dev/bus/usb/002/004"),
                listOf(smart),
            ),
        )
        assertTrue(
            CaptureSourceFilter.isBlockedAudio(
                AudioInputIdentity("webcam microphone", null),
                emptyList(),
            ),
        )
        val capture = UsbIdentity(0x345F, "/dev/bus/usb/001/008", "USB3.0 HD Video Capture", null)
        assertFalse(
            CaptureSourceFilter.isBlockedAudio(
                AudioInputIdentity("USB3.0 HD Video Capture", "/dev/bus/usb/001/008"),
                listOf(smart, capture),
            ),
        )
        assertTrue(
            CaptureSourceFilter.isBlockedAudio(
                AudioInputIdentity("Board Mic Array", null),
                listOf(smart, capture),
            ),
        )
    }

    @Test
    fun acceptsGenericUsbAudioFromTheCaptureStickWhileTheSmartWebcamIsConnected() {
        val smart = UsbIdentity(
            vendorId = CaptureSourceFilter.SMART_VENDOR_ID,
            deviceName = "/dev/bus/usb/001/003",
            productName = "SMART Ultra HD Camera",
            manufacturerName = "SMART Technologies",
            productId = 0x0001,
            hasAudioInterface = true,
        )
        val stick = UsbIdentity(
            vendorId = 0x534D,
            deviceName = "/dev/bus/usb/001/005",
            productName = null,
            manufacturerName = "MACROSILICON",
            productId = 0x2109,
            hasAudioInterface = true,
        )
        val inputs = listOf(
            AudioInputIdentity("SMART Ultra HD Camera", "card=1;device=0"),
            AudioInputIdentity("USB Audio", "card=2;device=0"),
            AudioInputIdentity(null, "card=2;device=0"),
            AudioInputIdentity("USB Audio CODEC", "card=2;device=0"),
        )
        val indexes = CaptureSourceFilter.selectAudioInputIndexes(
            inputs,
            "MACROSILICON",
            listOf(smart, stick),
            stick.deviceName,
        )
        assertEquals(listOf(1, 2, 3), indexes)
        assertEquals(
            "use: generic USB audio from the capture card",
            CaptureSourceFilter.audioDecision(inputs[1], listOf(smart, stick), "MACROSILICON", stick.deviceName),
        )
    }

    @Test
    fun acceptsTheSameGenericAudioWhenTheStickVendorIsNotMacrosilicon() {
        val smart = UsbIdentity(
            vendorId = CaptureSourceFilter.SMART_VENDOR_ID,
            deviceName = "/dev/bus/usb/002/002",
            productName = "SWC-120UHD",
            manufacturerName = "SMART Technologies",
            hasAudioInterface = true,
        )
        val stick = UsbIdentity(
            vendorId = 0x345F,
            deviceName = "/dev/bus/usb/002/006",
            productName = null,
            manufacturerName = null,
            productId = 0x2109,
            hasAudioInterface = true,
        )
        assertFalse(
            CaptureSourceFilter.isBlockedAudio(
                AudioInputIdentity("USB Audio Device", "card=3;device=0"),
                listOf(smart, stick),
                playingDeviceName = stick.deviceName,
            ),
        )
        assertEquals(stick.deviceName, CaptureSourceFilter.directUsbDeviceName(stick.deviceName, listOf(smart, stick)))
    }

    @Test
    fun doesNotGuessGenericAudioWhenTwoCaptureCardsHaveUac() {
        val smart = UsbIdentity(
            vendorId = CaptureSourceFilter.SMART_VENDOR_ID,
            deviceName = "/dev/bus/usb/001/003",
            productName = "SMART Ultra HD Camera",
            manufacturerName = "SMART Technologies",
            hasAudioInterface = true,
        )
        val first = UsbIdentity(0x534D, "/dev/bus/usb/001/005", null, null, productId = 0x2109, hasAudioInterface = true)
        val second = UsbIdentity(0x1B80, "/dev/bus/usb/001/006", null, null, productId = 0xE409, hasAudioInterface = true)
        assertTrue(
            CaptureSourceFilter.isBlockedAudio(
                AudioInputIdentity("USB Audio", "card=4;device=0"),
                listOf(smart, first, second),
                playingDeviceName = first.deviceName,
            ),
        )
        assertEquals(first.deviceName, CaptureSourceFilter.directUsbDeviceName(first.deviceName, listOf(smart, first, second)))
        assertNull(CaptureSourceFilter.directUsbDeviceName("/dev/bus/usb/001/003", listOf(smart)))
    }

    @Test
    fun stillBlocksGenericAudioWhenTheCaptureCardHasNoAudioInterface() {
        val smart = UsbIdentity(
            vendorId = CaptureSourceFilter.SMART_VENDOR_ID,
            deviceName = "/dev/bus/usb/001/003",
            productName = "SWC-120UHD",
            manufacturerName = "SMART Technologies",
            hasAudioInterface = true,
        )
        val stick = UsbIdentity(0x534D, "/dev/bus/usb/001/005", null, "MACROSILICON", hasAudioInterface = false)
        assertTrue(
            CaptureSourceFilter.isBlockedAudio(
                AudioInputIdentity("USB Audio", "card=2;device=0"),
                listOf(smart, stick),
                playingDeviceName = stick.deviceName,
            ),
        )
    }

    @Test
    fun extraVendorIdsAreExcludedAlongsideSmart() {
        val policy = ExclusionPolicy(excludeSmartDevices = true, extraVendorIds = setOf(0x9999))
        assertTrue(
            CaptureSourceFilter.isExcludedUsb(0x9999, "USB3.0 HD Video Capture", null, policy = policy),
        )
        val devices = listOf(
            usb(CaptureSourceFilter.SMART_VENDOR_ID, "SMART Ultra HD Camera", "SMART Technologies"),
            usb(0x9999, "Other Grabber", "Other"),
            usb(0x345F, "USB3.0 HD Video Capture", "MacroSilicon"),
        )
        val chosen = CaptureSourceFilter.selectUsbVideo(
            devices,
            { it.vendorId },
            { it.productName },
            { it.manufacturerName },
            policy,
        )
        assertEquals(listOf("USB3.0 HD Video Capture"), chosen.map { it.productName })
    }

    @Test
    fun turningOffSmartExclusionListsTheWebcamWhenItIsTheOnlyVideoDevice() {
        val policy = ExclusionPolicy(excludeSmartDevices = false)
        assertFalse(
            CaptureSourceFilter.isExcludedUsb(
                CaptureSourceFilter.SMART_VENDOR_ID,
                "SMART Ultra HD Camera",
                "SMART Technologies",
                policy = policy,
            ),
        )
        val devices = listOf(
            usb(CaptureSourceFilter.SMART_VENDOR_ID, "SMART Ultra HD Camera", "SMART Technologies"),
        )
        val chosen = CaptureSourceFilter.selectUsbVideo(
            devices,
            { it.vendorId },
            { it.productName },
            { it.manufacturerName },
            policy,
        )
        assertEquals(listOf("SMART Ultra HD Camera"), chosen.map { it.productName })
    }

    @Test
    fun anExtraExcludedDongleDoesNotBecomeTheGenericAudioInput() {
        val policy = ExclusionPolicy(extraVendorIds = setOf(0x9999))
        val extra = UsbIdentity(
            vendorId = 0x9999,
            deviceName = "/dev/bus/usb/001/004",
            productName = "Other Dongle",
            manufacturerName = null,
            hasAudioInterface = true,
        )
        val stick = UsbIdentity(
            vendorId = 0x534D,
            deviceName = "/dev/bus/usb/001/005",
            productName = null,
            manufacturerName = "MACROSILICON",
            productId = 0x2109,
            hasAudioInterface = true,
        )
        assertTrue(
            CaptureSourceFilter.isBlockedAudio(
                AudioInputIdentity("USB Audio", "/dev/bus/usb/001/004"),
                listOf(extra, stick),
                playingDeviceName = stick.deviceName,
                policy = policy,
            ),
        )
        assertEquals(
            "use: generic USB audio from the capture card",
            CaptureSourceFilter.audioDecision(
                AudioInputIdentity("USB Audio", "card=2;device=0"),
                listOf(extra, stick),
                "MACROSILICON",
                stick.deviceName,
                policy,
            ),
        )
        assertEquals(
            stick.deviceName,
            CaptureSourceFilter.directUsbDeviceName(stick.deviceName, listOf(extra, stick), policy),
        )
    }

    private fun usb(vendorId: Int, product: String?, manufacturer: String?): UsbIdentity {
        return UsbIdentity(vendorId, "/dev/bus/usb/001/001", product, manufacturer)
    }
}
