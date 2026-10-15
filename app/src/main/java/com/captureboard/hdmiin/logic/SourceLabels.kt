package com.captureboard.hdmiin.logic

import java.util.Locale

object SourceLabels {
    fun usbTitle(productName: String?, manufacturerName: String?): String {
        val product = productName?.trim().orEmpty()
        val manufacturer = manufacturerName?.trim().orEmpty()
        return when {
            product.isNotEmpty() -> product
            manufacturer.isNotEmpty() -> manufacturer
            else -> "USB HDMI capture"
        }
    }

    fun usbSubtitle(vendorId: Int, productId: Int): String {
        return String.format(Locale.US, "USB capture · %04x:%04x", vendorId, productId)
    }

    fun cameraTitle(cameraId: String): String = "External camera $cameraId"

    fun cameraSubtitle(): String = "System camera · lower overhead when the board exposes it"
}
