package com.hcc.dvdplayer.logic

import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDiagnosticsFormatterTest {
    @Test
    fun listsUsbIdentityAndWhetherEachInputIsUsed() {
        val text = AudioDiagnosticsFormatter.format(
            versionName = "1.0.4",
            playingDeviceName = "/dev/bus/usb/001/005",
            productHint = "MACROSILICON",
            directUsb = "waiting for a USB input",
            devices = listOf(
                AudioDiagnosticsFormatter.UsbDeviceLine(
                    vendorId = 0x0B8C,
                    productId = 0x0001,
                    manufacturer = "SMART Technologies",
                    product = "SMART Ultra HD Camera",
                    deviceName = "/dev/bus/usb/001/003",
                    hasAudio = true,
                    playing = false,
                    interfaces = listOf(
                        AudioDiagnosticsFormatter.InterfaceLine(0, 0, 14, 1, 0, emptyList()),
                    ),
                ),
                AudioDiagnosticsFormatter.UsbDeviceLine(
                    vendorId = 0x534D,
                    productId = 0x2109,
                    manufacturer = "MACROSILICON",
                    product = null,
                    deviceName = "/dev/bus/usb/001/005",
                    hasAudio = true,
                    playing = true,
                    interfaces = listOf(
                        AudioDiagnosticsFormatter.InterfaceLine(
                            number = 3,
                            alternate = 1,
                            clazz = 1,
                            subclass = 2,
                            protocol = 0,
                            endpoints = listOf(
                                AudioDiagnosticsFormatter.EndpointLine(0x82, "iso", "in", 256),
                            ),
                        ),
                    ),
                ),
            ),
            inputs = listOf(
                AudioDiagnosticsFormatter.InputLine(
                    typeName = "USB device",
                    productName = "USB Audio",
                    address = "card=2;device=0",
                    rates = listOf(96_000),
                    channels = listOf(1),
                    decision = "use: generic USB audio from the capture card",
                ),
                AudioDiagnosticsFormatter.InputLine(
                    typeName = "built-in mic",
                    productName = "Built-in Microphone",
                    address = null,
                    rates = listOf(48_000),
                    channels = listOf(2),
                    decision = "blocked: not a USB input",
                ),
            ),
        )
        assertTrue(text.contains("DVD Player 1.0.4"))
        assertTrue(text.contains("0b8c:0001"))
        assertTrue(text.contains("534d:2109"))
        assertTrue(text.contains("(none)"))
        assertTrue(text.contains("playing: yes"))
        assertTrue(text.contains("if3 alt1 class=1 sub=2"))
        assertTrue(text.contains("ep 0x82 in iso max=256"))
        assertTrue(text.contains("card=2;device=0"))
        assertTrue(text.contains("use: generic USB audio from the capture card"))
        assertTrue(text.contains("blocked: not a USB input"))
        assertTrue(text.contains("waiting for a USB input"))
    }
}