package com.captureboard.hdmiin.source

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.captureboard.hdmiin.logic.SourceLabels
import com.captureboard.hdmiin.logic.UsbVideoFilter

data class ListedSource(
    val kind: Kind,
    val title: String,
    val subtitle: String,
    val usbDeviceName: String?,
    val cameraId: String?,
    val audioHint: String?,
) {
    enum class Kind { USB_UVC, CAMERA2_EXTERNAL }

    fun signature(): String {
        return "$kind:${usbDeviceName.orEmpty()}:${cameraId.orEmpty()}:$title:$subtitle"
    }
}

class SourceCatalog(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager: UsbManager? = appContext.getSystemService(UsbManager::class.java)
    private val cameraManager: CameraManager? = appContext.getSystemService(CameraManager::class.java)

    fun usbHostAvailable(): Boolean = usbManager != null

    fun list(): List<ListedSource> {
        val usb = usbSources()
        val cameras = cameraSources()
        return usb + cameras
    }

    fun findUsbDevice(deviceName: String): UsbDevice? {
        val manager = usbManager ?: return null
        return manager.deviceList.values.firstOrNull { it.deviceName == deviceName }
    }

    private fun usbSources(): List<ListedSource> {
        val manager = usbManager ?: return emptyList()
        return manager.deviceList.values
            .filter { device -> UsbVideoFilter.matches(device.deviceClass, device.deviceSubclass, interfacesOf(device)) }
            .sortedWith(compareBy({ it.vendorId }, { it.productId }, { it.deviceName }))
            .map { device ->
                val product = safeString { device.productName }
                val manufacturer = safeString { device.manufacturerName }
                ListedSource(
                    kind = ListedSource.Kind.USB_UVC,
                    title = SourceLabels.usbTitle(product, manufacturer),
                    subtitle = SourceLabels.usbSubtitle(device.vendorId, device.productId),
                    usbDeviceName = device.deviceName,
                    cameraId = null,
                    audioHint = product ?: manufacturer,
                )
            }
    }

    private fun cameraSources(): List<ListedSource> {
        val manager = cameraManager ?: return emptyList()
        val ids = try {
            manager.cameraIdList
        } catch (_: Exception) {
            return emptyList()
        }
        return ids.mapNotNull { id ->
            val characteristics = try {
                manager.getCameraCharacteristics(id)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            if (!isExternal(characteristics)) return@mapNotNull null
            ListedSource(
                kind = ListedSource.Kind.CAMERA2_EXTERNAL,
                title = SourceLabels.cameraTitle(id),
                subtitle = SourceLabels.cameraSubtitle(),
                usbDeviceName = null,
                cameraId = id,
                audioHint = null,
            )
        }.sortedBy { it.cameraId }
    }

    private fun isExternal(characteristics: CameraCharacteristics): Boolean {
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        return level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL
    }

    private fun interfacesOf(device: UsbDevice): List<UsbVideoFilter.InterfaceSnapshot> {
        val snapshots = ArrayList<UsbVideoFilter.InterfaceSnapshot>(device.interfaceCount)
        for (index in 0 until device.interfaceCount) {
            val intf = device.getInterface(index)
            snapshots += UsbVideoFilter.InterfaceSnapshot(intf.interfaceClass, intf.interfaceSubclass)
        }
        return snapshots
    }

    private fun safeString(read: () -> String?): String? {
        return try {
            read()?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null
        }
    }
}
