package com.hcc.dvdplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UacDescriptorParserTest {
    @Test
    fun readsTheMono96kDescriptorAsStereo48k() {
        val stream = UacDescriptorParser.findStream(axhdcapAudio())
        requireNotNull(stream)
        assertEquals(2, stream.controlInterface)
        assertEquals(2, stream.featureUnitId)
        assertEquals(2, stream.terminalChannels)
        assertEquals(3, stream.streamingInterface)
        assertEquals(1, stream.alternateSetting)
        assertEquals(1, stream.formatChannels)
        assertEquals(16, stream.bitsPerSample)
        assertEquals(96_000, stream.sampleRate)
        assertEquals(0x82, stream.endpointAddress)
        assertEquals(256, stream.maxPacketSize)
        assertEquals(48_000, stream.playbackSampleRate)
        assertEquals(2, stream.playbackChannels)
        assertTrue(stream.repackMono96kAsStereo48k)
        assertTrue(stream.summary().contains("48000 Hz x 2"))
        assertTrue(stream.summary().contains("96000 Hz x 1"))
    }

    @Test
    fun leavesARealStereo48kStreamAlone() {
        val stream = UacDescriptorParser.findStream(stereo48k())
        requireNotNull(stream)
        assertEquals(48_000, stream.playbackSampleRate)
        assertEquals(2, stream.playbackChannels)
        assertFalse(stream.repackMono96kAsStereo48k)
        assertEquals(0x82, stream.endpointAddress)
    }

    @Test
    fun ignoresVideoOnlyDescriptors() {
        assertNull(UacDescriptorParser.findStream(videoOnly()))
        assertNull(UacDescriptorParser.findStream(byteArrayOf()))
    }

    @Test
    fun decodesHighSpeedPacketMultipliers() {
        assertEquals(256, UacDescriptorParser.packetBytes(0x0100))
        assertEquals(3072, UacDescriptorParser.packetBytes(0x1400))
    }

    private fun axhdcapAudio(): ByteArray {
        return descriptor(
            audioControl(interfaceNumber = 2, terminalChannels = 2, featureUnitId = 2),
            iface(number = 3, alt = 0, clazz = 1, sub = 2, endpoints = 0),
            iface(number = 3, alt = 1, clazz = 1, sub = 2, endpoints = 1),
            formatType(channels = 1, rate = 96_000),
            isoIn(address = 0x82, wMaxPacketSize = 0x0100),
        )
    }

    private fun stereo48k(): ByteArray {
        return descriptor(
            audioControl(interfaceNumber = 2, terminalChannels = 2, featureUnitId = 5),
            iface(number = 3, alt = 1, clazz = 1, sub = 2, endpoints = 1),
            formatType(channels = 2, rate = 48_000),
            isoIn(address = 0x82, wMaxPacketSize = 0x00C0),
        )
    }

    private fun videoOnly(): ByteArray {
        return descriptor(
            iface(number = 0, alt = 0, clazz = 14, sub = 1, endpoints = 0),
            iface(number = 1, alt = 1, clazz = 14, sub = 2, endpoints = 1),
            isoIn(address = 0x83, wMaxPacketSize = 0x1400),
        )
    }

    private fun audioControl(interfaceNumber: Int, terminalChannels: Int, featureUnitId: Int): ByteArray {
        return descriptor(
            iface(number = interfaceNumber, alt = 0, clazz = 1, sub = 1, endpoints = 0),
            byteArrayOf(
                12, 0x24, 0x02, 1,
                0x02, 0x06,
                0,
                terminalChannels.toByte(),
                0x03, 0x00,
                0, 0,
            ),
            byteArrayOf(
                8, 0x24, 0x06,
                featureUnitId.toByte(),
                1,
                1,
                0x01,
                0,
            ),
        )
    }

    private fun formatType(channels: Int, rate: Int): ByteArray {
        return byteArrayOf(
            11, 0x24, 0x02, 1,
            channels.toByte(),
            2,
            16,
            1,
            (rate and 0xff).toByte(),
            ((rate shr 8) and 0xff).toByte(),
            ((rate shr 16) and 0xff).toByte(),
        )
    }

    private fun isoIn(address: Int, wMaxPacketSize: Int): ByteArray {
        return byteArrayOf(
            9, 5,
            address.toByte(),
            5,
            (wMaxPacketSize and 0xff).toByte(),
            ((wMaxPacketSize shr 8) and 0xff).toByte(),
            4, 0, 0,
        )
    }

    private fun iface(number: Int, alt: Int, clazz: Int, sub: Int, endpoints: Int): ByteArray {
        return byteArrayOf(9, 4, number.toByte(), alt.toByte(), endpoints.toByte(), clazz.toByte(), sub.toByte(), 0, 0)
    }

    private fun descriptor(vararg parts: ByteArray): ByteArray {
        return parts.reduce { left, right -> left + right }
    }
}
