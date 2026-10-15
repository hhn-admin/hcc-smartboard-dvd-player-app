package com.hcc.dvdplayer.logic

/**
 * Same match the UVCAndroid device filter uses: USB video class (14), or a
 * composite miscellaneous device (class 239, subclass 2). Matching is applied
 * to the device descriptor and to every interface, because HDMI capture dongles
 * are almost always composite (video + audio) and advertise class 239 at the
 * device level with a video interface underneath.
 */
object UsbVideoFilter {
    const val CLASS_VIDEO = 14
    const val CLASS_MISC = 239
    const val SUBCLASS_MISC_COMMON = 2

    data class InterfaceSnapshot(
        val interfaceClass: Int,
        val interfaceSubclass: Int,
    )

    fun matches(deviceClass: Int, deviceSubclass: Int, interfaces: List<InterfaceSnapshot>): Boolean {
        if (isUvcDescriptor(deviceClass, deviceSubclass)) return true
        return interfaces.any { isUvcDescriptor(it.interfaceClass, it.interfaceSubclass) }
    }

    fun isUvcDescriptor(clazz: Int, subclass: Int): Boolean {
        if (clazz == CLASS_VIDEO) return true
        return clazz == CLASS_MISC && subclass == SUBCLASS_MISC_COMMON
    }
}
