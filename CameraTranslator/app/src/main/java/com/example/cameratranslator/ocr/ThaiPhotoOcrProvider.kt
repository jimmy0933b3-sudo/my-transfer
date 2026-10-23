package com.example.cameratranslator.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.example.cameratranslator.core.models.TextElement
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thai script isn't supported by Google ML Kit Text Recognition v2.
 * Use the existing Tesseract4Android library with official Thai traineddata.
 * Download once over HTTPS, cache only in app-private storage.
 */
class ThaiPhotoOcrProvider(private val context: Context) {
    companion object {
        // Pin to an immutable upstream source revision.
        private const val MODEL_URL =
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/" +
                "87416418657359cb625c412a48b6e1d6d41c29bd/tha.traineddata"
        private const val MIN_MODEL_BYTES = 300_000L
        private const val MAX_MODEL_BYTES = 8_000_000L
    }

    private fun modelDirectory() = File(context.filesDir, "tesseract/tessdata")

    fun isModelInstalled(): Boolean {
        val model = File(modelDirectory(), "tha.traineddata")
        return model.isFile && model.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES
    }

    private fun ensureModel() {
        val directory = modelDirectory()
        if (!directory.exists() && !directory.mkdirs()) {
            error("無法建立泰文 OCR 模型資料夾")
        }
        val model = File(directory, "tha.traineddata")
        if (model.isFile && model.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES) return
        if (model.exists()) model.delete()
        val temp = File(directory, "tha.traineddata.tmp")
        temp.delete()
        try {
            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 25_000
            connection.instanceFollowRedirects = true
            try {
                if (connection.responseCode != 200) {
                    error("下載泰文模型失敗（HTTP " + connection.responseCode + "）")
                }
                connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_MODEL_BYTES) error("泰文模型檔案大小異常")
                            output.write(buffer, 0, n)
                        }
                        output.flush()
                    }
                }
                if (temp.length() !in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
                    error("泰文模型下載不完整")
                }
                if (!temp.renameTo(model)) error("無法保存泰文 OCR 模型")
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            temp.delete()
            throw IllegalStateException(
                "泰文 OCR 模型首次使用需要網路，下載失敗：" + e.message, e
            )
        }
    }

    suspend fun downloadModel() = withContext(Dispatchers.IO) { ensureModel() }

    suspend fun recognize(bitmap: Bitmap): List<TextElement> = withContext(Dispatchers.IO) {
        ensureModel()
        val tess = TessBaseAPI()
        try {
            val directory = modelDirectory().parentFile ?: error("泰文模型目錄無效")
            check(tess.init(directory.absolutePath, "tha")) {
                "泰文 OCR 模型初始化失敗"
            }
            tess.setImage(bitmap)
            val recognized = tess.getUTF8Text().orEmpty().trim()
            val rows = recognized.lines()
                .map(String::trim)
                .filter { it.isNotBlank() && PhotoOcrLanguages.containsThai(it) }
                .distinct()
                .take(24)
            rows.mapIndexed { index, row ->
                TextElement(
                    id = "th:" + index + ":" + row.hashCode(),
                    originalText = row,
                    sourceLanguage = "th",
                    boundingBox = Rect(0, 0, bitmap.width, bitmap.height),
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height
                )
            }
        } finally {
            tess.recycle()
        }
    }
}
