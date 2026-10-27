package com.hcc.dvdplayer.source

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a PNG into Pictures/DVD Player.
 *
 * API 29 and newer use MediaStore and need no storage permission.
 * API 24–28 write to the public Pictures directory when
 * WRITE_EXTERNAL_STORAGE is granted, and otherwise to the app's own pictures
 * directory so the frame is still kept.
 */
class SnapshotSaver(context: Context) {
    private val appContext = context.applicationContext

    data class Saved(
        val publicPictures: Boolean,
        val location: String,
    )

    fun save(bitmap: Bitmap, legacyStorageGranted: Boolean): Saved? {
        val name = fileName()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveMediaStore(bitmap, name)
        } else if (legacyStorageGranted) {
            saveFile(bitmap, name, publicDirectory(), publicPictures = true)
        } else {
            saveFile(bitmap, name, appDirectory(), publicPictures = false)
        }
    }

    private fun saveMediaStore(bitmap: Bitmap, name: String): Saved? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    error("compress")
                }
            } ?: error("stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Saved(publicPictures = true, location = RELATIVE_PATH)
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun saveFile(bitmap: Bitmap, name: String, directory: File?, publicPictures: Boolean): Saved? {
        if (directory == null) return null
        return try {
            if (!directory.exists() && !directory.mkdirs()) return null
            val file = File(directory, name)
            FileOutputStream(file).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) return null
            }
            if (publicPictures) {
                MediaScannerConnection.scanFile(
                    appContext,
                    arrayOf(file.absolutePath),
                    arrayOf("image/png"),
                    null,
                )
            }
            Saved(publicPictures = publicPictures, location = file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    private fun publicDirectory(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            FOLDER,
        )
    }

    private fun appDirectory(): File? {
        val base = appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: appContext.filesDir
        return File(base, FOLDER)
    }

    private fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "dvd-$stamp.png"
    }

    companion object {
        const val FOLDER = "DVD Player"
        const val RELATIVE_PATH = "Pictures/DVD Player"
    }
}
