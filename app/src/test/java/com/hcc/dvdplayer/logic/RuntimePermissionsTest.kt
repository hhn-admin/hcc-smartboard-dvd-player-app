package com.hcc.dvdplayer.logic

import com.hcc.dvdplayer.source.ListedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePermissionsTest {
    @Test
    fun usbCaptureDoesNotNeedCameraOrMicrophone() {
        assertTrue(RuntimePermissions.requiredFor(usb()).isEmpty())
    }

    @Test
    fun camera2NeedsCameraAndMicrophone() {
        assertEquals(
            setOf(RuntimePermissions.CAMERA, RuntimePermissions.RECORD_AUDIO),
            RuntimePermissions.requiredFor(camera()),
        )
        assertEquals("android.permission.CAMERA", RuntimePermissions.CAMERA)
        assertEquals("android.permission.RECORD_AUDIO", RuntimePermissions.RECORD_AUDIO)
    }

    private fun usb(): ListedSource {
        return ListedSource(
            kind = ListedSource.Kind.USB_UVC,
            title = "DVD capture card",
            subtitle = "USB source",
            usbDeviceName = "/dev/bus/usb/004/014",
            cameraId = null,
            audioHint = null,
            captureCard = true,
        )
    }

    private fun camera(): ListedSource {
        return ListedSource(
            kind = ListedSource.Kind.CAMERA2_EXTERNAL,
            title = "External camera",
            subtitle = "System camera",
            usbDeviceName = null,
            cameraId = "0",
            audioHint = null,
            captureCard = true,
        )
    }
}
