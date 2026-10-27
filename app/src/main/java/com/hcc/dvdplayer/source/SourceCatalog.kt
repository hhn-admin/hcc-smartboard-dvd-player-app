package com.hcc.dvdplayer.source

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hcc.dvdplayer.logic.CaptureSourceFilter
import com.hcc.dvdplayer.logic.ExclusionPolicy
import com.hcc.dvdplayer.logic.SourceLabels
import com.hcc.dvdplayer.logic.UsbVideoFilter

data class ListedSource(
    val kind: Kind,
    val title: String,
    val subtitle: String,
    val usbDeviceName: String?,
    val cameraId: String?,
    val audioHint: String?,
    val captureCard: Boolean = false,
    val vendorId: Int = 0,
    val productId: Int = 0,
) {
    enum class Kind { USB_UVC, CAMERA2_EXTERNAL }

    fun signature(): String {
        return "$kind:${usbDeviceName.orEmpty()}:${cameraId.orEmpty()}:$vendorId:$productId:$title:$subtitle"
    }
}

class SourceCatalog(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager: UsbManager? = appContext.getSystemService(UsbManager::class.java)
    private val cameraManager: CameraManager? = appContext.getSystemService(CameraManager::class.java)

    fun usbHostAvailable(): Boolean = usbManager != null

    fun list(policy: ExclusionPolicy = ExclusionPolicy.DEFAULT): List<ListedSource> {
        val usb = usbSources(policy)
        if (usb.isNotEmpty()) return usb
        return cameraSources(policy)
    }

    fun findUsbDevice(deviceName: String): UsbDevice? {
        val manager = usbManager ?: return null
        return manager.deviceList.values.firstOrNull { it.deviceName == deviceName }
    }

    private fun usbSources(policy: ExclusionPolicy): List<ListedSource> {
        val manager = usbManager ?: return emptyList()
        val rows = manager.deviceList.values.mapNotNull { device ->
            if (!UsbVideoFilter.matches(device.deviceClass, device.deviceSubclass, interfacesOf(device))) {
                return@mapNotNull null
            }
            UsbRow(
                device = device,
                product = safeString { device.productName },
                manufacturer = safeString { device.manufacturerName },
            )
        }
        return CaptureSourceFilter.selectUsbVideo(
            devices = rows,
            vendorId = { it.device.vendorId },
            productName = { it.product },
            manufacturerName = { it.manufacturer },
            policy = policy,
        ).sortedWith(compareBy({ it.device.vendorId }, { it.device.productId }, { it.device.deviceName }))
            .map { row ->
                ListedSource(
                    kind = ListedSource.Kind.USB_UVC,
                    title = SourceLabels.usbTitle(row.product, row.manufacturer),
                    subtitle = SourceLabels.usbSubtitle(row.device.vendorId, row.device.productId),
                    usbDeviceName = row.device.deviceName,
                    cameraId = null,
                    audioHint = row.product ?: row.manufacturer,
                    captureCard = CaptureSourceFilter.looksLikeCaptureCard(
                        row.product,
                        row.manufacturer,
                        policy = policy,
                    ),
                    vendorId = row.device.vendorId,
                    productId = row.device.productId,
                )
            }
    }

    private fun cameraSources(policy: ExclusionPolicy): List<ListedSource> {
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
            val labels = listOf(id)
            if (!CaptureSourceFilter.allowCamera2(labels, listedUsbCount = 0, policy = policy)) return@mapNotNull null
            ListedSource(
                kind = ListedSource.Kind.CAMERA2_EXTERNAL,
                title = SourceLabels.cameraTitle(id),
                subtitle = SourceLabels.cameraSubtitle(),
                usbDeviceName = null,
                cameraId = id,
                audioHint = null,
                captureCard = true,
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

    private data class UsbRow(
        val device: UsbDevice,
        val product: String?,
        val manufacturer: String?,
    )

    private fun safeString(read: () -> String?): String? {
        return try {
            read()?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null
        }
    }
}
