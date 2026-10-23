package com.example.cameratranslator.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object TranslatedPhotoSaver {
    /** On Android 10+, store directly in the gallery without storage permission. */
    suspend fun save(context: Context, bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val name = "CameraTranslator_" + System.currentTimeMillis() + ".jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CameraTranslator")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("無法新增相簿照片")
            try {
                resolver.openOutputStream(uri)?.use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, stream)) {
                        "JPEG 編碼失敗"
                    }
                } ?: error("無法開啟照片儲存位置")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "已儲存至相簿 Pictures/CameraTranslator"
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            // Scoped app-specific directory avoids requiring legacy storage
            // permissions. Photos remain available from a file manager.
            val directory = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "CameraTranslator"
            )
            directory.mkdirs()
            val target = File(directory, name)
            try {
                FileOutputStream(target).use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) {
                        "JPEG 編碼失敗"
                    }
                }
                "已儲存至 " + target.absolutePath
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }
    }
}
