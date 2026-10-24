package com.example.cameratranslator.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.cameratranslator.core.models.TextElement
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * True on-device PP-OCRv5/PP-OCRv6 inference (det + rec) via the upstream
 * PaddleOCR Android SDK, OpenCV and ONNX Runtime. Never sends photos to cloud.
 */
class PaddleMobileOcrProvider(private val context: Context) {
    val store = PaddleMobileModelStore(context)
    private val lock = Mutex()
    private var loaded: PaddleMobileModelStore.Version? = null
    private var engine: PaddleOCR? = null

    suspend fun recognize(
        bitmap: Bitmap, selectedSource: String, version: PaddleMobileModelStore.Version
    ): List<TextElement> = withContext(Dispatchers.IO) {
        if (!store.ready(version)) error(version.label + " 尚未下載完畢")
        lock.withLock {
            if (loaded != version || engine == null) {
                engine?.release()
                engine = null
                loaded = null
                if (!OpenCVUtils.init(context)) error("OpenCV 未能在手機啟動")
                val paths = store.files(version)
                engine = PaddleOCR.create(
                    context,
                    PaddleOCRConfig(
                        detLimitSideLen = 960,
                        detLimitType = "max",
                        detMaxSideLimit = 3000,
                        recScoreThresh = 0.32f,
                        recBatchSize = 1
                    ),
                    EngineConfig(numThreads = 4),
                    detModelAssetPath = paths.det.absolutePath,
                    recModelAssetPath = paths.rec.absolutePath,
                    recConfigAssetPath = paths.vocab.absolutePath
                )
                loaded = version
            }
            val result = engine!!.recognize(bitmap)
            Log.i("CameraTranslator", version.label + " detected " +
                result.results.size + " lines in " + result.totalTimeMs + "ms")
            result.results.mapIndexedNotNull { index, item ->
                val word = item.text.trim()
                val box = item.box.points
                if (word.length < 2 || box.size != 4) return@mapIndexedNotNull null
                val left = box.minOf { it.x }.toInt().coerceIn(0, bitmap.width)
                val top = box.minOf { it.y }.toInt().coerceIn(0, bitmap.height)
                val right = box.maxOf { it.x }.toInt().coerceIn(0, bitmap.width)
                val bottom = box.maxOf { it.y }.toInt().coerceIn(0, bitmap.height)
                if (right <= left || bottom <= top || item.confidence < 0.32f) {
                    return@mapIndexedNotNull null
                }
                val language = when {
                    selectedSource != "auto" -> selectedSource
                    word.any { it in '\u0e00'..'\u0e7f' } -> "th"
                    word.any { it in '\u3040'..'\u30ff' } -> "ja"
                    word.any { it in '\u3400'..'\u9fff' } -> "zh"
                    else -> "en"
                }
                TextElement(
                    id = version.name + ":" + index + ":" + word.hashCode(),
                    originalText = word,
                    boundingBox = Rect(left, top, right, bottom),
                    sourceLanguage = language,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height
                )
            }
        }
    }

    suspend fun release() = lock.withLock {
        engine?.release()
        engine = null
        loaded = null
    }
}
