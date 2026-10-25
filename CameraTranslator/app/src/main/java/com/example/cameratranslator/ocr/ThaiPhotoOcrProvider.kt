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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Genuine Thai photo OCR with Tesseract4Android, not a Latin ML Kit fallback.
 *
 * The single official tessdata_fast model is pinned to a commit and verified
 * with its Git blob SHA-1. Partial downloads are kept for HTTP Range resumption.
 * Only verified files are ever used to initialize Tesseract.
 */
class ThaiPhotoOcrProvider(private val context: Context) {
    companion object {
        private const val MODEL_URL =
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/" +
                "87416418657359cb625c412a48b6e1d6d41c29bd/tha.traineddata"
        private const val MAX_FILE_SIZE = 5_000_000L
        // Multiple provider instances exist: one in ViewModel and one in MLKitOcrProvider.
        private val modelLock = Any()
    }

    private val directory = File(context.filesDir, "tesseract/tessdata")
    private val model = File(directory, "tha.traineddata")
    private val partial = File(directory, "tha.traineddata.part")

    fun isModelInstalled(): Boolean = ThaiModelIntegrity.verified(model)

    /**
     * The callback receives coarse 0-100 percentages, safe for the Settings UI.
     * Never reports success merely because a file of similar size exists.
     */
    suspend fun downloadModel(onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        ensureModel(onProgress)
    }

    private fun ensureModel(onProgress: (Int) -> Unit = {}) {
        synchronized(modelLock) {
            if (isModelInstalled()) {
                onProgress(100)
                return
            }
            if (!directory.exists() && !directory.mkdirs()) {
                error("無法建立泰文 OCR 模型目錄")
            }
            if (model.exists() && !model.delete()) error("無法清除無效泰文模型")
            if (partial.length() > ThaiModelIntegrity.BYTES) partial.delete()
            try {
                // Try twice if the server rejects an outdated partial download.
                for (attempt in 0..1) {
                    val offset = partial.takeIf { it.isFile }?.length() ?: 0L
                    if (offset == ThaiModelIntegrity.BYTES) {
                        if (installVerifiedPartial()) {
                            onProgress(100)
                            return
                        }
                        partial.delete()
                    }
                    val start = partial.takeIf { it.isFile }?.length() ?: 0L
                    val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 12_000
                        readTimeout = 25_000
                        instanceFollowRedirects = true
                        setRequestProperty("Accept-Encoding", "identity")
                        if (start > 0L) setRequestProperty("Range", "bytes=$start-")
                    }
                    try {
                        val code = connection.responseCode
                        if (code == 416 && attempt == 0) {
                            partial.delete()
                            continue
                        }
                        if (code != 200 && code != 206) error("HTTP $code")
                        val append = start > 0L && code == 206
                        if (append) {
                            val range = connection.getHeaderField("Content-Range").orEmpty()
                            if (!range.startsWith("bytes $start-")) {
                                partial.delete()
                                if (attempt == 0) continue
                                error("伺服器回傳錯誤的續傳範圍")
                            }
                        }
                        // A server returning 200 ignored Range. Restart safely
                        // rather than appending duplicated bytes.
                        var count = if (append) start else 0L
                        var lastPercent = -1
                        connection.inputStream.use { input ->
                            FileOutputStream(partial, append).buffered().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n == -1) break
                                    count += n
                                    if (count > MAX_FILE_SIZE ||
                                        count > ThaiModelIntegrity.BYTES) {
                                        error("泰文 OCR 模型長度異常")
                                    }
                                    output.write(buffer, 0, n)
                                    val percent = (100L * count /
                                        ThaiModelIntegrity.BYTES).toInt().coerceIn(0, 99)
                                    if (percent >= lastPercent + 5) {
                                        lastPercent = percent
                                        onProgress(percent)
                                    }
                                }
                            }
                        }
                        if (count != ThaiModelIntegrity.BYTES) {
                            // Keep the partial file so the next tap resumes.
                            error("下載中斷，已保留 $count 位元組，請點重新下載續傳")
                        }
                        if (!installVerifiedPartial()) {
                            partial.delete()
                            error("泰文模型校驗失敗，請重新下載")
                        }
                        onProgress(100)
                        return
                    } finally {
                        connection.disconnect()
                    }
                }
                error("無法完成泰文 OCR 模型續傳")
            } catch (e: Exception) {
                Log.w("CameraTranslator", "Thai traineddata download failed", e)
                // Preserve the .part file after interruptions for resume,
                // but never mark it installed.
                throw IllegalStateException("泰文 OCR 模型下載失敗：" +
                    (e.message ?: "請檢查網路"), e)
            }
        }
    }

    private fun installVerifiedPartial(): Boolean {
        if (!ThaiModelIntegrity.verified(partial)) return false
        if (model.exists() && !model.delete()) error("無法替換舊模型")
        if (!partial.renameTo(model)) error("無法儲存已驗證泰文模型")
        return ThaiModelIntegrity.verified(model)
    }

    suspend fun recognize(
        bitmap: Bitmap, latinDateHints: List<ThaiPhotoLinePolicy.Line> = emptyList()
    ): List<TextElement> = withContext(Dispatchers.IO) {
        ensureModel()
        val tess = TessBaseAPI()
        try {
            val root = directory.parentFile ?: error("泰文 OCR 目錄無效")
            check(tess.init(root.absolutePath, "tha")) {
                "無法初始化泰文字形辨識引擎"
            }
            // General layout detects headlines, but an otherwise successful
            // pass regularly misses small isolated dates on posters. Always
            // run sparse mode as independent segmentation of the same bitmap.
            val auto = recognizeLines(tess, bitmap, TessBaseAPI.PageSegMode.PSM_AUTO, 25f)
            val sparse = recognizeLines(
                tess, bitmap, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT, 25f
            )
            val firstPass = ThaiPhotoLinePolicy.merge(auto, sparse)
            val recoveryRegions = ThaiPhotoLinePolicy.recoveryRegions(
                firstPass.filter { it.confidence >= .55f }, bitmap.width, bitmap.height)
            val recovered = recoveryRegions.flatMap { region ->
                // The gap can contain pale-blue Thai headlines; the final
                // tail may contain pale lettering inside a red date ribbon.
                listOf(false, true).mapNotNull { ribbon ->
                    recognizeRecoveryLine(tess, bitmap, region, ribbon)
                }
            }
            val merged = ThaiPhotoLinePolicy.merge(auto + recovered, sparse)
            val withDates = ThaiPhotoLinePolicy.attachLatinDateRanges(merged, latinDateHints)
            val trusted = withDates.filter { line ->
                line.confidence >= .55f ||
                    (line.confidence >= .36f &&
                        ThaiPhotoLinePolicy.corroborated(line, auto, sparse)) ||
                    (line.confidence >= .30f &&
                        ThaiPhotoLinePolicy.isCalendarLabel(line.text))
            }
            com.example.cameratranslator.core.DiagnosticLog.record(
                "THAI_OCR", "AUTO候選=" + auto.size +
                    "；SPARSE候選=" + sparse.size +
                    "；局部重辨區域=" + recoveryRegions.size +
                    "；局部候選=" + recovered.size +
                    "；去重合併=" + merged.size +
                    "；信心通過=" + trusted.size
            )
            trusted.mapIndexed { index, line ->
                TextElement(
                    id = "th:" + index + ":" + line.text.hashCode(),
                    originalText = line.text,
                    sourceLanguage = "th",
                    boundingBox = Rect(line.left, line.top, line.right, line.bottom),
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    ocrConfidence = line.confidence
                )
            }
        } finally {
            tess.recycle()
        }
    }

    /**
     * Re-scan only bands inferred from trusted Thai lines. Strong local
     * contrast restores pale-blue titles and reverse-contrast date ribbons.
     * Keep candidates only when the Thai model actually recognizes words.
     */
    private fun recognizeRecoveryLine(
        tess: TessBaseAPI,
        original: Bitmap,
        region: ThaiPhotoLinePolicy.RecoveryRegion,
        ribbon: Boolean
    ): ThaiPhotoLinePolicy.Line? {
        val width = region.right - region.left
        val height = region.bottom - region.top
        if (width < 50 || height < 24) return null
        val pixels = IntArray(width * height)
        original.getPixels(pixels, 0, width, region.left, region.top, width, height)
        val output = IntArray(pixels.size) { android.graphics.Color.WHITE }
        if (ribbon) {
            // Restrict inverse-contrast processing to a substantial red
            // ribbon. Do not invert the light poster background or toolbar.
            val counts = IntArray(height)
            for (y in 0 until height) for (x in 0 until width) {
                val px = pixels[y * width + x]
                val red = android.graphics.Color.red(px)
                val green = android.graphics.Color.green(px)
                val blue = android.graphics.Color.blue(px)
                if (red > 135 && red > green * 1.45f &&
                    red > blue * 1.12f) {
                    counts[y]++
                }
            }
            val peak = counts.maxOrNull() ?: 0
            if (peak < width * .17f) return null
            val rows = (0 until height).filter { counts[it] >= peak * .55f }
            if (rows.size < 5) return null
            // Ignore isolated red decorations at the far side of the
            // poster. The actual banner occupies a continuous x-range
            // through many selected rows, not one stray colored pixel.
            val columnCounts = IntArray(width)
            for (y in rows) for (x in 0 until width) {
                val px = pixels[y * width + x]
                val red = android.graphics.Color.red(px)
                val green = android.graphics.Color.green(px)
                val blue = android.graphics.Color.blue(px)
                if (red > 135 && red > green * 1.45f &&
                    red > blue * 1.12f) columnCounts[x]++
            }
            val columnThreshold = rows.size * .25f
            var runStart = -1
            var bestStart = -1
            var bestEnd = -1
            for (x in 0..width) {
                val qualifies = x < width && columnCounts[x] >= columnThreshold
                if (qualifies && runStart < 0) runStart = x
                if (!qualifies && runStart >= 0) {
                    if (x - runStart > bestEnd - bestStart) {
                        bestStart = runStart
                        bestEnd = x
                    }
                    runStart = -1
                }
            }
            if (bestStart < 0 || bestEnd - bestStart < width * .15f) return null
            val x1 = (bestStart - 3).coerceAtLeast(0)
            val x2 = (bestEnd + 3).coerceAtMost(width - 1)
            val y1 = (rows.first() - 2).coerceAtLeast(0)
            val y2 = (rows.last() + 2).coerceAtMost(height - 1)
            for (y in y1..y2) for (x in x1..x2) {
                val px = pixels[y * width + x]
                val brightness = (android.graphics.Color.red(px) * .299f +
                    android.graphics.Color.green(px) * .587f +
                    android.graphics.Color.blue(px) * .114f)
                output[y * width + x] =
                    if (brightness >= 166f) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE
            }
        } else {
            // Threshold relative to the observed pale background.
            val sampled = pixels.filterIndexed { index, _ ->
                (index / width) % 3 == 0 && (index % width) % 4 == 0
            }.map { px ->
                (android.graphics.Color.red(px) * .299f +
                    android.graphics.Color.green(px) * .587f +
                    android.graphics.Color.blue(px) * .114f).toInt()
            }.sorted()
            val background = sampled.getOrElse(sampled.size * 9 / 10) { 225 }
            val threshold = (background - 24).coerceIn(120, 230)
            for (i in pixels.indices) {
                val px = pixels[i]
                val brightness = (android.graphics.Color.red(px) * .299f +
                    android.graphics.Color.green(px) * .587f +
                    android.graphics.Color.blue(px) * .114f)
                output[i] = if (brightness < threshold) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE
            }
        }
        val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val upscaled: Bitmap
        try {
            base.setPixels(output, 0, width, 0, 0, width, height)
            upscaled = Bitmap.createScaledBitmap(base, width * 2, height * 2, false)
        } finally {
            base.recycle()
        }
        try {
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
            tess.setImage(upscaled)
            tess.getUTF8Text()
            val iterator = tess.getResultIterator() ?: return null
            try {
                iterator.begin()
                val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                val value = iterator.getUTF8Text(level)?.trim().orEmpty()
                val box = iterator.getBoundingRect(level) ?: return null
                val confidence = (iterator.confidence(level) / 100f).coerceIn(0f, 1f)
                if (confidence < .55f ||
                    !ThaiPhotoLinePolicy.plausible(value) || box.isEmpty) return null
                val left = region.left + box.left / 2
                val top = region.top + box.top / 2
                val right = region.left + (box.right + 1) / 2
                val bottom = region.top + (box.bottom + 1) / 2
                if (left !in 0 until original.width ||
                    top !in 0 until original.height ||
                    right > original.width || bottom > original.height ||
                    right - left < 12 || bottom - top < 10) return null
                return ThaiPhotoLinePolicy.Line(value, left, top, right, bottom, confidence)
            } finally {
                iterator.delete()
            }
        } finally {
            upscaled.recycle()
        }
    }

    private fun recognizeLines(
        tess: TessBaseAPI,
        bitmap: Bitmap,
        pageMode: Int,
        minConfidence: Float
    ): List<ThaiPhotoLinePolicy.Line> {
        tess.setPageSegMode(pageMode)
        tess.setImage(bitmap)
        tess.getUTF8Text() // Force OCR before opening the result iterator.
        val iterator = tess.getResultIterator() ?: return emptyList()
        try {
            val results = mutableListOf<ThaiPhotoLinePolicy.Line>()
            val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                val box = iterator.getBoundingRect(level)
                val confidence = iterator.confidence(level)
                if (ThaiPhotoLinePolicy.plausible(text) &&
                    confidence >= minConfidence && box != null && !box.isEmpty &&
                    box.left >= 0 && box.top >= 0 &&
                    box.right <= bitmap.width && box.bottom <= bitmap.height &&
                    box.width() >= 10 && box.height() >= 10 &&
                    box.width() < bitmap.width * 0.92f &&
                    box.height() < bitmap.height * 0.5f
                ) {
                    results.add(
                        ThaiPhotoLinePolicy.Line(text,
                            box.left, box.top, box.right, box.bottom,
                            (confidence / 100f).coerceIn(0f, 1f))
                    )
                }
            } while (iterator.next(level) && results.size < 48)
            return results
        } finally {
            iterator.delete()
        }
    }}
