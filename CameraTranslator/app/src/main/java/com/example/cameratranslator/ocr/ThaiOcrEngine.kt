package com.example.cameratranslator.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.cameratranslator.core.models.TextElement
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Optional offline Thai-script OCR, separate from ML Kit translation models.
 * ML Kit Text Recognition v2 does not implement a Thai-script recognizer.
 * Uses an upstream-pinned, SHA1-verified tessdata_fast model.
 */
class ThaiOcrEngine(private val context: Context) {
    companion object {
        const val MODEL_URL =
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/" +
                "87416418657359cb625c412a48b6e1d6d41c29bd/tha.traineddata"
        const val MODEL_BLOB_SHA1 = "ea28de3985668cac00d261c5b91f023ea66e19cd"
        const val MODEL_SIZE = 1072600L
        const val MAX_DOWNLOAD_BYTES = 5_000_000L
    }

    private val root = File(context.filesDir, "thai-ocr")
    private val tessdata = File(root, "tessdata")
    private val model = File(tessdata, "tha.traineddata")

    fun isInstalled(): Boolean = model.isFile && model.length() == MODEL_SIZE &&
        verifyGitBlobSha1(model)

    suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext true
        tessdata.mkdirs()
        val partial = File(tessdata, "tha.traineddata.part")
        try {
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 25000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            try {
                if (connection.responseCode !in 200..299) error("HTTP " + connection.responseCode)
                val declared = connection.contentLengthLong
                if (declared > MAX_DOWNLOAD_BYTES) error("Model too large")
                var bytes = 0L
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            bytes += n
                            if (bytes > MAX_DOWNLOAD_BYTES) error("Model size exceeded")
                            output.write(buffer, 0, n)
                        }
                    }
                }
                if (bytes != MODEL_SIZE || !verifyGitBlobSha1(partial)) error("Model checksum mismatch")
                if (model.exists() && !model.delete()) error("Cannot replace old model")
                if (!partial.renameTo(model)) error("Cannot save model")
                true
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w("CameraTranslator", "Thai OCR model download failed", e)
            partial.delete()
            false
        }
    }

    private fun verifyGitBlobSha1(file: File): Boolean = try {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(("blob " + file.length() + "\u0000").toByteArray(Charsets.UTF_8))
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sha1.update(buffer, 0, n)
            }
        }
        sha1.digest().joinToString("") { "%02x".format(it) } == MODEL_BLOB_SHA1
    } catch (_: Exception) { false }

    suspend fun recognize(bitmap: Bitmap): List<TextElement> = withContext(Dispatchers.Default) {
        if (!isInstalled()) return@withContext emptyList()
        val tess = TessBaseAPI()
        try {
            if (!tess.init(root.absolutePath, "tha")) error("Thai OCR init failed")
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            tess.setImage(bitmap)
            val output = tess.utF8Text?.trim().orEmpty()
            if (output.isBlank()) return@withContext emptyList()
            // Tesseract returns newline-separated text, not reliable per-line
            // coordinates. High-resolution photo mode displays these as a
            // separate original/translation list instead of placing fake boxes.
            output.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .chunked(3)
                .map { it.joinToString("\n") }
                .take(18)
                .mapIndexed { index, txt ->
                    TextElement(
                        id = "thai-photo-" + index,
                        originalText = txt,
                        boundingBox = Rect(0, 0, bitmap.width, bitmap.height),
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                        sourceLanguage = "th"
                    )
                }
        } finally {
            tess.recycle()
        }
    }
}
