package com.example.cameratranslator.ocr

import android.content.Context
import android.graphics.Bitmap
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
        if (selectedSource == "th") return mergePhotoParagraphs(thai.recognize(bitmap))
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
        ).take(32)
    }

    /**
     * Photo OCR text blocks are visual blocks, not grammatical sentences.
     * Postprocess only captured photos: keep original per-block coordinates
     * for correct image replacement while joining closely stacked fragments.
     */
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
        return all.take(24)
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
                    sourceLanguage = forcedLanguage ?: when {
                        kanaChars.containsMatchIn(text) -> "ja"
                        hanChars.containsMatchIn(text) -> "zh"
                        else -> "en"
                    }
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
