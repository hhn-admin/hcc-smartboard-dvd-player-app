package com.hcc.dvdplayer.logic

/**
 * Reads a UAC1 streaming interface out of a raw USB descriptor blob.
 *
 * The layout this cares about is the one on cheap HDMI capture sticks: an
 * audio-control interface (input terminal + mute feature unit) followed by an
 * audio-streaming interface whose non-zero alternate setting has PCM and an
 * isochronous IN endpoint. Vendor and product ids are not consulted.
 */
object UacDescriptorParser {
    data class Stream(
        val controlInterface: Int,
        val featureUnitId: Int?,
        val terminalChannels: Int,
        val streamingInterface: Int,
        val alternateSetting: Int,
        val formatChannels: Int,
        val bitsPerSample: Int,
        val sampleRate: Int,
        val endpointAddress: Int,
        val maxPacketSize: Int,
        val playbackSampleRate: Int,
        val playbackChannels: Int,
    ) {
        val repackMono96kAsStereo48k: Boolean
            get() = formatChannels == 1 &&
                sampleRate == 96_000 &&
                bitsPerSample == 16 &&
                playbackSampleRate == 48_000 &&
                playbackChannels == 2

        fun summary(): String {
            val played = "$playbackSampleRate Hz x $playbackChannels"
            val wire = "$sampleRate Hz x $formatChannels"
            val quirk = if (repackMono96kAsStereo48k) " (descriptor says $wire, played as $played)" else ""
            return "iface $streamingInterface alt $alternateSetting ep 0x%02x %s%s".format(
                endpointAddress,
                played,
                quirk,
            )
        }
    }

    fun findStream(raw: ByteArray): Stream? {
        if (raw.isEmpty()) return null
        var offset = 0
        var ifClass = -1
        var ifSub = -1
        var ifNum = -1
        var alt = -1
        var terminalChannels = 0
        var featureUnitId: Int? = null
        var controlInterface = -1
        var fmtChannels = -1
        var fmtBits = -1
        var fmtRate = -1
        val found = ArrayList<Stream>()

        while (offset + 1 < raw.size) {
            val length = raw[offset].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) break
            val type = raw[offset + 1].toInt() and 0xff
            when (type) {
                USB_DT_INTERFACE -> if (length >= 9) {
                    ifNum = u8(raw, offset + 2)
                    alt = u8(raw, offset + 3)
                    ifClass = u8(raw, offset + 5)
                    ifSub = u8(raw, offset + 6)
                    fmtChannels = -1
                    fmtBits = -1
                    fmtRate = -1
                    if (ifClass == USB_CLASS_AUDIO && ifSub == SUBCLASS_CONTROL) {
                        controlInterface = ifNum
                        terminalChannels = 0
                        featureUnitId = null
                    }
                }
                CS_INTERFACE -> {
                    val subtype = if (length >= 3) u8(raw, offset + 2) else -1
                    if (ifClass == USB_CLASS_AUDIO && ifSub == SUBCLASS_CONTROL && subtype == AC_INPUT_TERMINAL && length >= 8) {
                        terminalChannels = u8(raw, offset + 7)
                    }
                    if (ifClass == USB_CLASS_AUDIO && ifSub == SUBCLASS_CONTROL && subtype == AC_FEATURE_UNIT && length >= 7) {
                        val controlSize = u8(raw, offset + 5)
                        if (controlSize >= 1 && (u8(raw, offset + 6) and 0x01) != 0) {
                            featureUnitId = u8(raw, offset + 3)
                        }
                    }
                    if (ifClass == USB_CLASS_AUDIO && ifSub == SUBCLASS_STREAMING && subtype == AS_FORMAT_TYPE && length >= 11) {
                        if (u8(raw, offset + 3) == FORMAT_TYPE_I) {
                            fmtChannels = u8(raw, offset + 4)
                            fmtBits = u8(raw, offset + 6)
                            fmtRate = chooseRate(raw, offset, length)
                        }
                    }
                }
                USB_DT_ENDPOINT -> if (
                    length >= 7 &&
                    ifClass == USB_CLASS_AUDIO &&
                    ifSub == SUBCLASS_STREAMING &&
                    alt > 0 &&
                    fmtChannels > 0 &&
                    fmtBits == 16 &&
                    fmtRate > 0
                ) {
                    val address = u8(raw, offset + 2)
                    val attributes = u8(raw, offset + 3)
                    val packet = packetBytes(u16(raw, offset + 4))
                    val isochronousIn = (address and USB_DIR_IN) != 0 && (attributes and 0x03) == ISOCHRONOUS
                    if (isochronousIn && packet > 0) {
                        val playback = playbackOf(fmtChannels, fmtRate, terminalChannels)
                        found += Stream(
                            controlInterface = controlInterface,
                            featureUnitId = featureUnitId,
                            terminalChannels = terminalChannels,
                            streamingInterface = ifNum,
                            alternateSetting = alt,
                            formatChannels = fmtChannels,
                            bitsPerSample = fmtBits,
                            sampleRate = fmtRate,
                            endpointAddress = address,
                            maxPacketSize = packet,
                            playbackSampleRate = playback.first,
                            playbackChannels = playback.second,
                        )
                    }
                }
            }
            offset += length
        }
        return found.firstOrNull()
    }

    fun packetBytes(wMaxPacketSize: Int): Int {
        val size = wMaxPacketSize and 0x7ff
        val transactions = ((wMaxPacketSize shr 11) and 0x3) + 1
        return size * transactions
    }

    private fun playbackOf(channels: Int, rate: Int, terminalChannels: Int): Pair<Int, Int> {
        if (channels == 1 && rate == 96_000 && terminalChannels >= 2) {
            return 48_000 to 2
        }
        return rate to channels.coerceIn(1, 2)
    }

    private fun chooseRate(raw: ByteArray, offset: Int, length: Int): Int {
        val freqType = u8(raw, offset + 7)
        if (freqType <= 0) return -1
        var chosen = -1
        for (index in 0 until freqType) {
            val pos = offset + 8 + index * 3
            if (pos + 3 > offset + length) break
            val rate = u24(raw, pos)
            if (chosen < 0) chosen = rate
            if (rate == 48_000) return rate
        }
        return chosen
    }

    private fun u8(raw: ByteArray, index: Int): Int = raw[index].toInt() and 0xff

    private fun u16(raw: ByteArray, index: Int): Int = u8(raw, index) or (u8(raw, index + 1) shl 8)

    private fun u24(raw: ByteArray, index: Int): Int {
        return u8(raw, index) or (u8(raw, index + 1) shl 8) or (u8(raw, index + 2) shl 16)
    }

    private const val USB_DT_INTERFACE = 0x04
    private const val USB_DT_ENDPOINT = 0x05
    private const val CS_INTERFACE = 0x24
    private const val USB_CLASS_AUDIO = 1
    private const val SUBCLASS_CONTROL = 1
    private const val SUBCLASS_STREAMING = 2
    private const val AC_INPUT_TERMINAL = 0x02
    private const val AC_FEATURE_UNIT = 0x06
    private const val AS_FORMAT_TYPE = 0x02
    private const val FORMAT_TYPE_I = 0x01
    private const val USB_DIR_IN = 0x80
    private const val ISOCHRONOUS = 0x01
}
