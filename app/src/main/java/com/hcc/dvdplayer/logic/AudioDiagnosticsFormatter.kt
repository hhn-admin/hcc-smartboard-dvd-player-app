package com.hcc.dvdplayer.logic

import java.util.Locale

/**
 * Text for the on-screen diagnostics a teacher can photograph. It is plain
 * data so the wording can be checked without a device.
 */
object AudioDiagnosticsFormatter {
    data class EndpointLine(
        val address: Int,
        val transfer: String,
        val direction: String,
        val maxPacket: Int,
    )

    data class InterfaceLine(
        val number: Int,
        val alternate: Int,
        val clazz: Int,
        val subclass: Int,
        val protocol: Int,
        val endpoints: List<EndpointLine>,
    )

    data class UsbDeviceLine(
        val vendorId: Int,
        val productId: Int,
        val manufacturer: String?,
        val product: String?,
        val deviceName: String?,
        val hasAudio: Boolean,
        val playing: Boolean,
        val interfaces: List<InterfaceLine>,
    )

    data class InputLine(
        val typeName: String,
        val productName: String?,
        val address: String?,
        val rates: List<Int>,
        val channels: List<Int>,
        val decision: String,
    )

    fun format(
        versionName: String,
        playingDeviceName: String?,
        productHint: String?,
        directUsb: String,
        devices: List<UsbDeviceLine>,
        inputs: List<InputLine>,
    ): String {
        val text = StringBuilder()
        text.append("DVD Player ").append(versionName).append('\n')
        text.append("USB audio diagnostics\n")
        text.append("Playing device: ").append(blank(playingDeviceName)).append('\n')
        text.append("Name hint: ").append(blank(productHint)).append('\n')
        text.append("Direct USB: ").append(directUsb).append('\n')
        text.append("\nUSB devices\n")
        if (devices.isEmpty()) {
            text.append("(none)\n")
        } else {
            devices.forEach { device -> text.append(deviceText(device)) }
        }
        text.append("\nAudio inputs\n")
        if (inputs.isEmpty()) {
            text.append("(none)\n")
        } else {
            inputs.forEach { input -> text.append(inputText(input)) }
        }
        text.append("\nLong-press the status line again, or tap Close, to hide this.\n")
        return text.toString()
    }

    private fun deviceText(device: UsbDeviceLine): String {
        val id = String.format(Locale.US, "%04x:%04x", device.vendorId, device.productId)
        val body = StringBuilder()
        body.append(id).append("  ").append(blank(device.product)).append('\n')
        body.append("  maker: ").append(blank(device.manufacturer)).append('\n')
        body.append("  path: ").append(blank(device.deviceName)).append('\n')
        body.append("  audio interface: ").append(if (device.hasAudio) "yes" else "no").append('\n')
        if (device.playing) body.append("  playing: yes\n")
        if (device.interfaces.isEmpty()) {
            body.append("  (no interfaces)\n")
        } else {
            device.interfaces.forEach { iface ->
                body.append("  if").append(iface.number)
                body.append(" alt").append(iface.alternate)
                body.append(" class=").append(iface.clazz)
                body.append(" sub=").append(iface.subclass)
                body.append(" proto=").append(iface.protocol)
                body.append(" endpoints=").append(iface.endpoints.size).append('\n')
                iface.endpoints.forEach { endpoint ->
                    body.append(
                        String.format(
                            Locale.US,
                            "    ep 0x%02x %s %s max=%d\n",
                            endpoint.address,
                            endpoint.direction,
                            endpoint.transfer,
                            endpoint.maxPacket,
                        ),
                    )
                }
            }
        }
        return body.toString()
    }

    private fun inputText(input: InputLine): String {
        return buildString {
            append(input.typeName).append("  \"").append(blank(input.productName)).append("\"\n")
            append("  address: ").append(blank(input.address)).append('\n')
            append("  rates: ").append(if (input.rates.isEmpty()) "(any)" else input.rates.joinToString(", ")).append('\n')
            append("  channels: ").append(if (input.channels.isEmpty()) "(any)" else input.channels.joinToString(", ")).append('\n')
            append("  ").append(input.decision).append('\n')
        }
    }

    private fun blank(value: String?): String = value?.trim()?.takeIf { it.isNotEmpty() } ?: "(none)"
}
