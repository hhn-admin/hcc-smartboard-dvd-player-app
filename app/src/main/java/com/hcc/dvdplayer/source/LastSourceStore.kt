package com.hcc.dvdplayer.source

import android.content.Context
import com.hcc.dvdplayer.logic.RememberedSource

/** Persists the last source that successfully showed a picture. */
class LastSourceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): RememberedSource? {
        if (!prefs.contains(KEY_KIND)) return null
        val kind = when (prefs.getString(KEY_KIND, null)) {
            ListedSource.Kind.USB_UVC.name -> ListedSource.Kind.USB_UVC
            ListedSource.Kind.CAMERA2_EXTERNAL.name -> ListedSource.Kind.CAMERA2_EXTERNAL
            else -> return null
        }
        return RememberedSource(
            kind = kind,
            vendorId = prefs.getInt(KEY_VENDOR, 0),
            productId = prefs.getInt(KEY_PRODUCT, 0),
            cameraId = prefs.getString(KEY_CAMERA, null),
        )
    }

    fun save(source: RememberedSource) {
        prefs.edit()
            .putString(KEY_KIND, source.kind.name)
            .putInt(KEY_VENDOR, source.vendorId)
            .putInt(KEY_PRODUCT, source.productId)
            .putString(KEY_CAMERA, source.cameraId)
            .apply()
    }

    companion object {
        private const val PREFS = "last_source"
        private const val KEY_KIND = "kind"
        private const val KEY_VENDOR = "vendor_id"
        private const val KEY_PRODUCT = "product_id"
        private const val KEY_CAMERA = "camera_id"
    }
}
