package com.hcc.dvdplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewSizeChooserTest {
    @Test
    fun prefersMjpeg1080pOverLargerUncompressedAndOver4kMjpeg() {
        val chosen = PreviewSizeChooser.choose(
            listOf(
                VideoMode(type = 5, width = 1920, height = 1080, fps = 30),
                VideoMode(type = PreviewSizeChooser.MJPEG_FRAME, width = 3840, height = 2160, fps = 30),
                VideoMode(type = PreviewSizeChooser.MJPEG_FRAME, width = 1280, height = 720, fps = 30),
                VideoMode(
                    type = PreviewSizeChooser.MJPEG_FRAME,
                    width = 1920,
                    height = 1080,
                    fps = 60,
                    fpsOptions = listOf(60, 30, 10),
                ),
            ),
        )
        assertEquals(1920, chosen?.width)
        assertEquals(1080, chosen?.height)
        assertEquals(30, chosen?.fps)
    }

    @Test
    fun fallsBackToSmallestModeWhenNothingFitsTheCap() {
        val chosen = PreviewSizeChooser.choose(
            listOf(
                VideoMode(type = PreviewSizeChooser.MJPEG_FRAME, width = 3840, height = 2160, fps = 30),
                VideoMode(type = PreviewSizeChooser.MJPEG_FRAME, width = 2560, height = 1440, fps = 30),
            ),
        )
        assertEquals(2560, chosen?.width)
        assertEquals(1440, chosen?.height)
    }

    @Test
    fun camera2CanIgnoreMjpegAndStillCapAt1080p() {
        val chosen = PreviewSizeChooser.choose(
            listOf(
                VideoMode(type = 0, width = 3840, height = 2160, fps = 30),
                VideoMode(type = 0, width = 1920, height = 1080, fps = 30),
                VideoMode(type = 0, width = 640, height = 480, fps = 30),
            ),
            preferMjpeg = false,
        )
        assertEquals(1920, chosen?.width)
        assertEquals(1080, chosen?.height)
    }

    @Test
    fun emptyListYieldsNothing() {
        assertNull(PreviewSizeChooser.choose(emptyList()))
    }

    @Test
    fun fitInsideLetterboxes() {
        assertEquals(1920 to 1080, PreviewSizeChooser.fitInside(1920, 1080, 1920, 1080))
        assertEquals(1000 to 562, PreviewSizeChooser.fitInside(1000, 1000, 16, 9))
        assertEquals(750 to 1000, PreviewSizeChooser.fitInside(1000, 1000, 3, 4))
    }

    @Test
    fun smallFramesScaleUpToTheScreenInsteadOfStayingAtNativePixels() {
        assertEquals(3840 to 2160, PreviewSizeChooser.fitInside(3840, 2160, 1920, 1080))
        assertEquals(2880 to 2160, PreviewSizeChooser.fitInside(3840, 2160, 640, 480))
        assertEquals(3840 to 2880, PreviewSizeChooser.cover(3840, 2160, 640, 480))
        assertEquals(1620 to 1080, PreviewSizeChooser.fitInside(1920, 1080, 720, 480))
    }

    @Test
    fun rankedModesPrefer1080pMjpegThenSmallerThenOversize() {
        val ranked = PreviewSizeChooser.ranked(
            listOf(
                VideoMode(type = PreviewSizeChooser.MJPEG_FRAME, width = 640, height = 480, fps = 30),
                VideoMode(type = PreviewSizeChooser.MJPEG_FRAME, width = 3840, height = 2160, fps = 30),
                VideoMode(type = PreviewSizeChooser.MJPEG_FRAME, width = 1280, height = 720, fps = 30),
                VideoMode(type = PreviewSizeChooser.MJPEG_FRAME, width = 1920, height = 1080, fps = 30),
                VideoMode(type = 5, width = 1920, height = 1080, fps = 30),
            ),
        )
        assertEquals(listOf(1920 to 1080, 1280 to 720, 640 to 480, 3840 to 2160), ranked.map { it.width to it.height })
    }
}
