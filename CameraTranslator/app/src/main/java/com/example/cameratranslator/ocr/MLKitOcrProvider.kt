package com.example.cameratranslator.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.cameratranslator.core.PhotoOrientationRanking
import kotlinx.coroutines.CancellationException
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.example.cameratranslator.core.models.TextElement
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.min

/**
 * Latin and Japanese recognizers are both required. The old implementation
 * ran only Japanese and incorrectly labeled every result "ja/en".
 */
class MLKitOcrProvider(context: Context) : OcrProvider {
    private val thai = ThaiPhotoOcrProvider(context.applicationContext)
    private val thaiPaddle = PaddleMobileOcrProvider(context.applicationContext)
    @Volatile var lastThaiPaddleUsed: Boolean = false
        private set
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val japanese = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val kanaChars = Regex("[\u3040-\u30ff]")
    private val hanChars = Regex("[\u3400-\u9fff]")

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override suspend fun recognizeText(image: ImageProxy, rotationDegrees: Int): List<TextElement> {
        val media = image.image ?: return emptyList()
        val input = InputImage.fromMediaImage(media, rotationDegrees)
        val crop = rotatedCrop(image.cropRect, image.width, image.height, rotationDegrees)
        val candidates = extract(input)
        return candidates.mapNotNull { element ->
            val rect = element.boundingBox ?: return@mapNotNull null
            val inside = Rect(rect)
            if (!inside.intersect(crop)) return@mapNotNull null
            element.copy(
                boundingBox = Rect(inside.left - crop.left, inside.top - crop.top,
                    inside.right - crop.left, inside.bottom - crop.top),
                imageWidth = crop.width().coerceAtLeast(1),
                imageHeight = crop.height().coerceAtLeast(1)
            )
        }
    }

    override suspend fun recognizeTextFromBitmap(bitmap: Bitmap): List<TextElement> =
        recognizeTextFromBitmap(bitmap, "auto")

    suspend fun recognizeTextFromBitmap(bitmap: Bitmap, selectedSource: String): List<TextElement> {
        require(PhotoOcrLanguages.supported(selectedSource)) { "Unsupported photo OCR language" }
        if (selectedSource == "th") {
            lastThaiPaddleUsed = false
            val latinBlocks = read(latin, InputImage.fromBitmap(bitmap, 0), "en")
            val rangePattern = Regex("""(?<![0-9])(?:[0-9๐-๙]{1,2})\s*[-–—~]\s*(?:[0-9๐-๙]{1,2})(?![0-9])""")
            val dateHints = latinBlocks.mapNotNull { item ->
                val box = item.boundingBox ?: return@mapNotNull null
                val numeric = rangePattern.find(item.originalText)?.value ?: return@mapNotNull null
                ThaiPhotoLinePolicy.Line(numeric, box.left, box.top,
                    box.right, box.bottom, 1f)
            }
            val thaiLines = thai.recognize(bitmap, dateHints)
            // Dedicated official Thai PP-OCRv5 recognizer, if downloaded,
            // provides independent glyph evidence. v5/v6 Chinese/Japanese
            // recognizers cannot be reused for Thai.
            val paddleBlocks = if (thaiPaddle.store.ready(
                    PaddleMobileModelStore.Version.THAI)) {
                try {
                    thaiPaddle.recognize(
                        bitmap, "th", PaddleMobileModelStore.Version.THAI
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.example.cameratranslator.core.DiagnosticLog.record(
                        "THAI_CROSSCHECK", "泰文 Paddle 推論失敗，保留 Tesseract", e
                    )
                    emptyList()
                }
            } else {
                com.example.cameratranslator.core.DiagnosticLog.record(
                    "THAI_CROSSCHECK", "泰文 Paddle 模型尚未下載，使用 Tesseract"
                )
                emptyList()
            }
            val paddleLines = paddleBlocks.mapNotNull { item ->
                val box = item.boundingBox ?: return@mapNotNull null
                if (item.sourceLanguage != "th") return@mapNotNull null
                ThaiPhotoLinePolicy.Line(item.originalText,
                    box.left, box.top, box.right, box.bottom,
                    item.ocrConfidence ?: 0f)
            }
            // English product labels printed among Thai copy are independent
            // source-language blocks. This branch used to discard them.
            val paddleEnglish = paddleBlocks.mapNotNull { item ->
                val box = item.boundingBox ?: return@mapNotNull null
                if (item.sourceLanguage != "en") return@mapNotNull null
                ThaiPhotoMixedScriptPolicy.Block(
                    item.originalText, box.left, box.top, box.right, box.bottom,
                    item.ocrConfidence ?: 0f
                )
            }.filter(ThaiPhotoMixedScriptPolicy::reliableEnglishCandidate)
            val fallbackEnglish = if (paddleBlocks.isEmpty()) {
                latinBlocks.mapNotNull { item ->
                    val box = item.boundingBox ?: return@mapNotNull null
                    // ML Kit on Thai letters is less trustworthy. Use only
                    // intact multiword Latin phrases without any digits.
                    val englishWords = Regex("[A-Za-z]{3,}")
                        .findAll(item.originalText).count()
                    if (englishWords < 2 || item.originalText.any(Char::isDigit))
                        return@mapNotNull null
                    ThaiPhotoMixedScriptPolicy.Block(
                        item.originalText, box.left, box.top, box.right,
                        box.bottom, .9f
                    )
                }.filter(ThaiPhotoMixedScriptPolicy::reliableEnglishCandidate)
            } else emptyList()
            val english = ThaiPhotoMixedScriptPolicy.groupEnglish(
                paddleEnglish.ifEmpty { fallbackEnglish }
            )
            lastThaiPaddleUsed = paddleBlocks.isNotEmpty()
            val compared = if (paddleLines.isEmpty()) thaiLines else {
                ThaiPhotoLinePolicy.combineIndependent(
                    thaiLines.mapNotNull { item ->
                        val box = item.boundingBox ?: return@mapNotNull null
                        ThaiPhotoLinePolicy.Line(item.originalText,
                            box.left, box.top, box.right, box.bottom,
                            item.ocrConfidence ?: 0f)
                    }, paddleLines
                ).mapIndexed { index, line ->
                    TextElement(
                        id = "th:validated:" + index + ":" + line.text.hashCode(),
                        originalText = line.text,
                        boundingBox = Rect(line.left, line.top, line.right, line.bottom),
                        sourceLanguage = "th",
                        imageWidth = bitmap.width, imageHeight = bitmap.height,
                        ocrConfidence = line.confidence
                    )
                }
            }
            // Tesseract trained only on Thai can hallucinate Thai glyphs over
            // browser URL bars in screenshots. Independently detect definite
            // Latin URLs and veto ONLY OCR rectangles sharing that URL area.
            // Never discard real Thai headlines based on a generic Latin OCR
            // guess, watermark, or arbitrary screen position.
            val urls = latinBlocks
                .mapNotNull { item ->
                    val box = item.boundingBox ?: return@mapNotNull null
                    if (!ThaiPhotoLinePolicy.isBrowserUrl(item.originalText))
                        return@mapNotNull null
                    ThaiPhotoLinePolicy.Line(
                        item.originalText, box.left, box.top,
                        box.right, box.bottom, 1f
                    )
                }
            val safeLines = compared.filterNot { item ->
                val rect = item.boundingBox ?: return@filterNot false
                val candidate = ThaiPhotoLinePolicy.Line(
                    item.originalText, rect.left, rect.top,
                    rect.right, rect.bottom, item.ocrConfidence ?: 0f
                )
                urls.any { ThaiPhotoLinePolicy.masksThaiLine(candidate, it) }
            }
            val mixedScriptSafe = safeLines.filterNot { item ->
                val box = item.boundingBox ?: return@filterNot false
                ThaiPhotoMixedScriptPolicy.isLatinHallucination(
                    ThaiPhotoMixedScriptPolicy.Block(
                        item.originalText, box.left, box.top,
                        box.right, box.bottom, item.ocrConfidence ?: 0f
                    ), paddleEnglish
                )
            }
            val englishElements = english.mapIndexed { i, line ->
                TextElement(
                    id = "en:thai-photo:" + i + ":" + line.text.hashCode(),
                    originalText = line.text,
                    sourceLanguage = "en",
                    boundingBox = Rect(line.left, line.top, line.right, line.bottom),
                    imageWidth = bitmap.width, imageHeight = bitmap.height,
                    ocrConfidence = line.confidence
                )
            }
            com.example.cameratranslator.core.DiagnosticLog.record(
                "THAI_MIXED_SCRIPT",
                "Paddle英文候選=" + paddleEnglish.size +
                    "；英文語意框=" + englishElements.size +
                    "；同區泰文幻覺排除=" + (safeLines.size - mixedScriptSafe.size)
            )
            com.example.cameratranslator.core.DiagnosticLog.record(
                "THAI_OCR", "拉丁網址證據=" + urls.size +
                    "；網址區域誤辨排除=" + (compared.size - safeLines.size)
            )
            com.example.cameratranslator.core.DiagnosticLog.record(
                "THAI_OCR", "網址安全過濾後=" + safeLines.size +
                    "；泰文逐行輸出=" + safeLines.size +
                    "；禁止跨行段落合併=true"
            )
            mixedScriptSafe.forEachIndexed { index, line ->
                val box = line.boundingBox
                com.example.cameratranslator.core.DiagnosticLog.photoBlock(
                    "THAI_FINAL", index, "th", line.originalText,
                    "框=" + (box?.toShortString() ?: "無") +
                        "；信心=" + (line.ocrConfidence?.toString() ?: "無")
                )
            }
            // Thai page-segmentation already yields printed lines. Joining them
            // again across nearby baselines turns three poster headings into
            // one giant rectangle and destroys the correct paint geometry.
            return (mixedScriptSafe + englishElements)
                .sortedWith(compareBy<TextElement> { it.boundingBox?.top ?: 0 }
                    .thenBy { it.boundingBox?.left ?: 0 })
        }
        val mlKit = mergePhotoParagraphs(
            extract(InputImage.fromBitmap(bitmap, 0), selectedSource).map {
                it.copy(imageWidth = bitmap.width, imageHeight = bitmap.height)
            }
        )
        // Auto remains fast and works without network. Thai recognition joins
        // only after its model has already been downloaded during manual Thai use.
        if (selectedSource != "auto" || !thai.isModelInstalled()) return mlKit
        val thaiLines = try {
            thai.recognize(bitmap)
        } catch (_: Exception) {
            emptyList()
        }
        return mergePhotoParagraphs(
            (thaiLines + mlKit).distinctBy { it.sourceLanguage + ":" + it.originalText }
        )
    }

    /**
     * Photo OCR text blocks are visual blocks, not grammatical sentences.
     * Postprocess only captured photos: keep original per-block coordinates
     * for correct image replacement while joining closely stacked fragments.
     */
    /**
     * EXIF alone cannot fix every sideways JPEG. Compare ML Kit's readable
     * horizontal text evidence on a small preview; keep ambiguous photos as-is.
     */
    suspend fun suggestUprightRotation(bitmap: Bitmap, selectedSource: String): Int {
        // Image dimensions describe camera sensor layout, not how the text
        // is oriented. Portrait photos frequently contain sideways labels.
        val maxEdge = max(bitmap.width, bitmap.height)
        val preview = if (maxEdge > 960) {
            val scale = 960f / maxEdge
            Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true
            )
        } else bitmap
        val recognizer = when (selectedSource) {
            "en" -> latin
            "zh" -> chinese
            else -> japanese
        }
        try {
            val scores = mutableMapOf<Int, PhotoOrientationRanking.Evidence>()
            for (rotation in listOf(0, 90, 180, 270)) {
                val text = try {
                    recognizer.process(InputImage.fromBitmap(preview, rotation)).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("CameraTranslator", "Photo orientation sample failed", e)
                    return 0
                }
                val lines = text.textBlocks.flatMap { it.lines }
                scores[rotation] = PhotoOrientationRanking.Evidence(
                    readableCharacters = lines.sumOf { line ->
                        line.text.count { it.isLetterOrDigit() }.coerceAtMost(60)
                    },
                    horizontalLines = lines.count { line ->
                        val box = line.boundingBox
                        box != null && box.width() > box.height() * 1.45f
                    },
                    verticalLines = lines.count { line ->
                        val box = line.boundingBox
                        box != null && box.height() > box.width() * 1.20f
                    }
                )
            }
            val chosen = PhotoOrientationRanking.choose(scores)
            Log.i("CameraTranslator", "Photo orientation suggestion: " +
                chosen + "; scores=" + scores)
            return chosen
        } finally {
            if (preview !== bitmap) preview.recycle()
        }
    }

    private fun mergePhotoParagraphs(elements: List<TextElement>): List<TextElement> {
        if (elements.isEmpty()) return elements
        val segments = elements.mapIndexedNotNull { index, element ->
            val rect = element.boundingBox ?: return@mapIndexedNotNull null
            PhotoOcrPostProcessor.Segment(
                text = element.originalText,
                language = element.sourceLanguage,
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,
                originalIndices = listOf(index)
            )
        }
        return PhotoOcrPostProcessor.combine(segments).map { segment ->
            val original = elements[segment.originalIndices.first()]
            original.copy(
                id = segment.originalIndices.joinToString(prefix = "photo:", separator = "+"),
                originalText = segment.text,
                boundingBox = Rect(segment.left, segment.top, segment.right, segment.bottom)
            )
        }
    }

    private suspend fun extract(input: InputImage, selectedSource: String = "auto"): List<TextElement> {
        val all = mutableListOf<TextElement>()
        // Manual source is authoritative. Auto uses only recognizer-specific scripts.
        if (selectedSource == "auto" || selectedSource == "en") {
            read(latin, input, if (selectedSource == "en") "en" else null)
                .filter { selectedSource != "auto" ||
                    (it.sourceLanguage == "en") }
                .forEach { addOrReplace(all, it) }
        }
        if (selectedSource == "auto" || selectedSource == "zh") {
            read(chinese, input, if (selectedSource == "zh") "zh" else null)
                .filter { selectedSource != "auto" || it.sourceLanguage == "zh" }
                .forEach { addOrReplace(all, it) }
        }
        if (selectedSource == "auto" || selectedSource == "ja") {
            read(japanese, input, if (selectedSource == "ja") "ja" else null)
                .filter { selectedSource != "auto" || it.sourceLanguage == "ja" }
                .forEach { addOrReplace(all, it) }
        }
        return all
    }

    private suspend fun read(
        recognizer: TextRecognizer, input: InputImage, forcedLanguage: String? = null
    ): List<TextElement> =
        try {
            recognizer.process(input).await().textBlocks.mapNotNull { block ->
                val text = block.text.trim()
                val bounds = block.boundingBox
                if (bounds == null || text.isBlank()) null else TextElement(
                    id = text.hashCode().toString() + ":" + bounds.left / 24 + ":" + bounds.top / 24,
                    originalText = text,
                    boundingBox = bounds,
                    sourceLanguage = PhotoOcrLanguages.resolveBlockSource(
                        text,
                        forcedLanguage,
                        when {
                            kanaChars.containsMatchIn(text) -> "ja"
                            hanChars.containsMatchIn(text) -> "zh"
                            else -> "en"
                        }
                    )
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    private fun addOrReplace(all: MutableList<TextElement>, candidate: TextElement) {
        val box = candidate.boundingBox ?: return
        val index = all.indexOfFirst { existing ->
            val other = existing.boundingBox ?: return@indexOfFirst false
            val w = max(0, min(other.right, box.right) - max(other.left, box.left))
            val h = max(0, min(other.bottom, box.bottom) - max(other.top, box.top))
            val minArea = min(other.width() * other.height(), box.width() * box.height())
            minArea > 0 && w.toFloat() * h / minArea > 0.45f
        }
        if (index < 0) {
            all.add(candidate)
            return
        }
        val existing = all[index]
        val replace = (candidate.sourceLanguage == "ja" && existing.sourceLanguage != "ja") ||
            (candidate.sourceLanguage == existing.sourceLanguage &&
                candidate.originalText.length > existing.originalText.length)
        if (replace) all[index] = candidate
    }

    private fun rotatedCrop(rect: Rect, width: Int, height: Int, rotation: Int): Rect =
        when (rotation) {
            90 -> Rect(height - rect.bottom, rect.left, height - rect.top, rect.right)
            180 -> Rect(width - rect.right, height - rect.bottom, width - rect.left, height - rect.top)
            270 -> Rect(rect.top, width - rect.right, rect.bottom, width - rect.left)
            else -> Rect(rect)
        }
}
