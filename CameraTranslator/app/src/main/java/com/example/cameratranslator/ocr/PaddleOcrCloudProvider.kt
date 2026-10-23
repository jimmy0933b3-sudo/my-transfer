package com.example.cameratranslator.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.example.cameratranslator.core.models.TextElement
import com.example.cameratranslator.translation.CloudTranslationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt

/**
 * Photo-only PP-OCRv5 path. Uploads a compressed photo to the authenticated
 * Cloudflare Worker, which calls a separately deployed PaddleOCR model server.
 * Offline mode MUST not instantiate/call this function.
 */
class PaddleOcrCloudProvider {
    suspend fun recognize(bitmap: Bitmap, language: String, token: String): List<TextElement>? =
        withContext(Dispatchers.IO) {
            if (token.length < 32 || language !in listOf("auto", "en", "zh", "ja", "th")) {
                return@withContext null
            }
            var connection: HttpsURLConnection? = null
            try {
                val maxEdge = 2048
                val ratio = minOf(1f, maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height))
                val uploadWidth = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
                val uploadHeight = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
                val upload = if (ratio < 1f) {
                    Bitmap.createScaledBitmap(bitmap, uploadWidth, uploadHeight, true)
                } else bitmap
                val bytes = try {
                    var jpeg: ByteArray? = null
                    for (quality in listOf(78, 63, 48)) {
                        val stream = ByteArrayOutputStream()
                        if (!upload.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                            error("Cannot encode JPEG")
                        }
                        val encoded = stream.toByteArray()
                        if (encoded.size <= 2_500_000) {
                            jpeg = encoded
                            break
                        }
                    }
                    jpeg ?: return@withContext null
                } finally {
                    if (upload !== bitmap) upload.recycle()
                }

                val json = JSONObject()
                    .put("language", language)
                    .put("image_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .toString()
                connection = URL(CloudTranslationProvider.WORKER_URL + "/ocr")
                    .openConnection() as HttpsURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 4500
                connection.readTimeout = 33000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { output ->
                    output.write(json.toByteArray(Charsets.UTF_8))
                }
                if (connection.responseCode != 200) {
                    // No upstream deployed (503), model error, or quota violation:
                    // silently fall back to local OCR; do not pretend Paddle ran.
                    Log.w("CameraTranslator",
                        "PaddleOCR unavailable, HTTP " + connection.responseCode)
                    return@withContext null
                }
                val result = connection.inputStream.bufferedReader(Charsets.UTF_8)
                    .use { JSONObject(it.readText()) }
                if (result.optString("engine") != "PP-OCRv5") return@withContext null
                val width = result.optInt("width")
                val height = result.optInt("height")
                if (width <= 0 || height <= 0) return@withContext null
                val blocks = result.optJSONArray("blocks") ?: return@withContext null
                val xRatio = bitmap.width.toFloat() / width
                val yRatio = bitmap.height.toFloat() / height
                val elements = mutableListOf<TextElement>()
                for (index in 0 until minOf(blocks.length(), 45)) {
                    val item = blocks.optJSONObject(index) ?: continue
                    val text = item.optString("text").trim()
                    val languageCode = item.optString("language")
                    val coords = item.optJSONArray("box") ?: continue
                    if (text.isBlank() || text.length > 1500 || coords.length() != 4 ||
                        languageCode !in listOf("en", "zh", "ja", "th") ||
                        item.optDouble("score", 0.0) < 0.45) continue
                    val left = (coords.optDouble(0) * xRatio).roundToInt()
                        .coerceIn(0, bitmap.width)
                    val top = (coords.optDouble(1) * yRatio).roundToInt()
                        .coerceIn(0, bitmap.height)
                    val right = (coords.optDouble(2) * xRatio).roundToInt()
                        .coerceIn(0, bitmap.width)
                    val bottom = (coords.optDouble(3) * yRatio).roundToInt()
                        .coerceIn(0, bitmap.height)
                    if (right - left < 8 || bottom - top < 8) continue
                    elements.add(TextElement(
                        id = "ppocr5:$index",
                        originalText = text,
                        boundingBox = Rect(left, top, right, bottom),
                        sourceLanguage = languageCode,
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height
                    ))
                }
                // Empty output is valid if Paddle completed with no recognized text.
                elements
            } catch (e: Exception) {
                Log.w("CameraTranslator", "PaddleOCR request failed; local OCR fallback", e)
                null
            } finally {
                connection?.disconnect()
            }
        }
}
