package com.hcc.dvdplayer.audio

/**
 * Isochronous IN transfers on the usbfs fd from [android.hardware.usb.UsbDeviceConnection].
 * Android's Java USB API still cannot read isochronous endpoints, which is how
 * UAC audio is carried.
 */
internal class IsochronousReader {
    private var handle: Long = 0
    private var loaded = false

    fun start(
        fd: Int,
        endpoint: Int,
        packetSize: Int,
        packets: Int,
        urbCount: Int,
        interfaceNumber: Int,
        alternate: Int,
    ): Boolean {
        if (handle != 0L) stop()
        if (fd < 0) return false
        if (!loaded) {
            System.loadLibrary("uaciso")
            loaded = true
        }
        val started = nativeStart(fd, endpoint, packetSize, packets, urbCount, interfaceNumber, alternate)
        handle = started
        return started != 0L
    }

    fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val current = handle
        if (current == 0L) return -1
        return nativeRead(current, buffer, timeoutMs)
    }

    fun stop() {
        val current = handle
        handle = 0
        if (current != 0L) nativeStop(current)
    }

    private external fun nativeStart(
        fd: Int,
        endpoint: Int,
        packetSize: Int,
        packets: Int,
        urbCount: Int,
        interfaceNumber: Int,
        alternate: Int,
    ): Long

    private external fun nativeRead(handle: Long, buffer: ByteArray, timeoutMs: Int): Int

    private external fun nativeStop(handle: Long)
}
