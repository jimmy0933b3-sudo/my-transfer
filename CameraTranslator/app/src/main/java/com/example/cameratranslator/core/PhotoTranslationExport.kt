package com.example.cameratranslator.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Export the translated *bitmap*, never just OCR overlay metadata. */
internal object PhotoTranslationExport {
    private fun filename(): String =
        "translated_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"

    fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Scoped MediaStore insertion without permission requires Android 10+.
            // Do not pretend success or write to inaccessible public directories.
            return null
        }
        val name = filename()
        val data = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraTranslator")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, data)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output)) {
                    "Failed JPEG encoding"
                }
            } ?: error("Cannot open gallery output stream")
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            uri
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    fun share(context: Context, bitmap: Bitmap) {
        val dir = File(context.cacheDir, "translated_photos").apply { mkdirs() }
        val file = File(dir, filename())
        file.outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it))
        }
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享翻譯照片"))
    }
}
