package com.example.cameratranslator.camera

import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.cameratranslator.core.MainViewModel
import com.example.cameratranslator.core.models.TextElement
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max
import kotlin.math.min

@Composable
fun CameraPreviewScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val controller = remember(context) {
        LifecycleCameraController(context).apply {
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS or CameraController.IMAGE_CAPTURE)
        }
    }
    val preview = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            this.controller = controller
        }
    }
    // Reconfigure OCR detectors when the user switches the source language.
    // Manual mode runs only one recognizer, improving responsiveness and avoiding
    // mixed-script false positives. Auto mode uses all supported scripts.
    val selectedSource = viewModel.sourceLanguage
    DisposableEffect(controller, owner, viewModel, selectedSource) {
        val latin: TextRecognizer? = if (selectedSource == "auto" || selectedSource == "en")
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) else null
        val japanese: TextRecognizer? = if (selectedSource == "auto" || selectedSource == "ja")
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) else null
        val chinese: TextRecognizer? = if (selectedSource == "auto" || selectedSource == "zh")
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) else null
        val recognizers = listOfNotNull(latin, japanese, chinese)

        viewModel.cameraController = controller
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(
                recognizers,
                CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                executor
            ) { result ->
                if (!viewModel.isPaused) {
                    val en = latin?.let {
                        result.getThrowable(it)?.let { error -> Log.w("CameraTranslator", "Latin OCR", error) }
                        result.getValue(it)?.let { value -> extractLines(value, selectedSource, "en") }
                    }.orEmpty()
                    val ja = japanese?.let {
                        result.getThrowable(it)?.let { error -> Log.w("CameraTranslator", "Japanese OCR", error) }
                        result.getValue(it)?.let { value -> extractLines(value, selectedSource, "ja") }
                    }.orEmpty()
                    val zh = chinese?.let {
                        result.getThrowable(it)?.let { error -> Log.w("CameraTranslator", "Chinese OCR", error) }
                        result.getValue(it)?.let { value -> extractLines(value, selectedSource, "zh") }
                    }.orEmpty()
                    // Do not mix the three OCR engines indiscriminately. English
                    // pages caused the CJK engines to hallucinate stray fragments.
                    val englishLetters = en.sumOf { candidate ->
                        candidate.originalText.count { it in 'A'..'Z' || it in 'a'..'z' }
                    }
                    val chosen = if (selectedSource == "auto" && englishLetters >= 40 &&
                        en.size >= 2
                    ) {
                        // Keep genuine Japanese kana, but suppress guessed Han-only
                        // fragments on an overwhelmingly Latin page.
                        mergeLines(en, emptyList(), ja)
                    } else mergeLines(en, zh, ja)
                    // Japanese text blocks should retain sentence context and
                    // punctuation. Re-grouping individual Japanese lines caused
                    // broken phrases and nonsensical Azure translations.
                    viewModel.onCameraTextElements(
                        if (selectedSource == "ja") chosen else groupParagraphs(chosen)
                    )
                }
            }
        )
        try {
            controller.bindToLifecycle(owner)
        } catch (e: Exception) {
            Log.e("CameraTranslator", "Unable to bind camera", e)
            viewModel.showCameraError()
        }
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            if (viewModel.cameraController === controller) viewModel.cameraController = null
            recognizers.forEach { it.close() }
        }
    }
    AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
}

private val kanaCharacters = Regex("[\u3040-\u30ff]")
private val hanCharacters = Regex("[\u3400-\u9fff]")
private val latinCharacters = Regex("[A-Za-z]")

/**
 * OCR engines trained for CJK scripts can hallucinate Chinese glyphs over
 * English packaging. Auto mode selects the recognizer by the actual script,
 * not merely by whichever engine produced some text.
 *
 * Do not discard Chinese/English mixed labels wholesale. Mixed Latin-heavy
 * candidates from the Chinese recognizer require a much stronger Han signal.
 */
private fun extractLines(result: Text, selectedSource: String, script: String): List<TextElement> =
    (if (script == "ja") {
        result.textBlocks.mapNotNull { block ->
            block.boundingBox?.let { box -> block.text.trim() to box }
        }
    } else {
        result.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                line.boundingBox?.let { box -> line.text.trim() to box }
            }
        }
    }).mapNotNull { (raw, box) ->
        val value = if (script == "ja") raw else raw.replace(Regex("\\s+"), " ")
        if (box.isEmpty || value.length < 2 || value.none { it.isLetter() }) return@mapNotNull null
        val kana = kanaCharacters.findAll(value).count()
        val han = hanCharacters.findAll(value).count()
        val latin = latinCharacters.findAll(value).count()
        // Latin-only product logos on Japanese packaging are often proper
        // names, e.g. TAKARA TOMY. Do not send them to JA→ZH translation,
        // which can turn the brand into nonsensical Chinese words.
        if (script == "ja" && kana == 0 && han == 0) return@mapNotNull null
        // Do not translate isolated words and partial line fragments on busy
        // pages: OCR may change these every frame and spam Azure/overlays.
        if (value.count { it.isLetter() } < 3) return@mapNotNull null
        if (script == "en" && !OcrLineQuality.acceptEnglish(value)) {
            return@mapNotNull null
        }
        val language = if (selectedSource != "auto") selectedSource else when (script) {
            "ja" -> "ja"
            "zh" -> "zh"
            else -> "en"
        }
        if (selectedSource == "auto") {
            val belongs = when (script) {
                "ja" -> kana >= 2 && kana >= han / 3
                "zh" -> OcrLineQuality.acceptChinese(value)
                else -> latin >= 2 && han == 0 && kana == 0
            }
            if (!belongs) return@mapNotNull null
        }
        TextElement(
            id = language + ":" + value.lowercase(),
            originalText = value,
            boundingBox = box,
            sourceLanguage = language,
            isViewCoordinates = true
        )
    }

/**
 * Deduplicate recognizer results spatially while preferring legible English
 * on predominantly Latin packaging. Previous code ALWAYS overwrote overlapping
 * Latin text with the Chinese recognizer's result, even when it was nonsense.
 */
private fun mergeLines(
    latin: List<TextElement>,
    chinese: List<TextElement>,
    japanese: List<TextElement>
): List<TextElement> {
    val output = latin.toMutableList()
    for (candidate in chinese + japanese) {
        val box = candidate.boundingBox ?: continue
        val index = output.indexOfFirst { existing ->
            val old = existing.boundingBox ?: return@indexOfFirst false
            val w = max(0, min(box.right, old.right) - max(box.left, old.left))
            val h = max(0, min(box.bottom, old.bottom) - max(box.top, old.top))
            val smallerArea = min(
                box.width().toLong() * box.height(),
                old.width().toLong() * old.height()
            ).coerceAtLeast(1L)
            (w.toLong() * h).toFloat() / smallerArea >= 0.50f
        }
        if (index == -1) {
            output.add(candidate)
        } else {
            val existing = output[index]
            val han = hanCharacters.findAll(candidate.originalText).count()
            val kana = kanaCharacters.findAll(candidate.originalText).count()
            val latinInExisting = latinCharacters.findAll(existing.originalText).count()
            val preferCandidate = when {
                candidate.sourceLanguage == "ja" -> kana >= 2
                candidate.sourceLanguage == "zh" && existing.sourceLanguage == "en" ->
                    han >= 3 && han >= latinInExisting * 2
                else -> false
            }
            if (preferCandidate) output[index] = candidate
        }
    }
    return output.sortedWith(
        compareBy({ it.boundingBox?.top ?: Int.MAX_VALUE }, { it.boundingBox?.left ?: 0 })
    ).take(48)
}

/**
 * Keep OCR/translation at paragraph granularity rather than drawing a new overlay
 * for every line. Joining happens AFTER deduplicating Latin/Japanese OCR lines so
 * one Japanese line does not replace a whole English paragraph.
 *
 * Only combine vertically neighboring lines in the same column and language.
 * Large gaps, different font sizes, or a second column remain separate boxes.
 */
private fun groupParagraphs(lines: List<TextElement>): List<TextElement> {
    class Paragraph(first: TextElement) {
        val parts = mutableListOf(first)
        val bounds = Rect(first.boundingBox!!)
        val language = first.sourceLanguage

        fun canAppend(next: TextElement): Boolean {
            if (next.sourceLanguage != language || parts.size >= 3) return false
            // One numbered homework sentence is one translation unit. Do
            // not merge two separately numbered lines into a paragraph.
            if (Regex("^\\d{1,2}[.)]\\s*").containsMatchIn(next.originalText)) return false
            val previousText = parts.last().originalText.trim()
            if (previousText.endsWith(".") || previousText.endsWith("!") ||
                previousText.endsWith("?")) return false
            val previous = parts.last().boundingBox ?: return false
            val candidate = next.boundingBox ?: return false
            val shortHeight = min(previous.height(), candidate.height()).toFloat()
            val tallHeight = max(previous.height(), candidate.height()).toFloat()
            if (shortHeight <= 0f || shortHeight / tallHeight < 0.64f) return false

            val verticalGap = candidate.top - previous.bottom
            if (verticalGap < -shortHeight * 0.2f || verticalGap > tallHeight * 0.85f) return false

            // A line wrapping within a paragraph normally retains the same left
            // edge or overlaps the preceding line. Avoid joining adjacent columns.
            val overlap = max(0, min(previous.right, candidate.right) - max(previous.left, candidate.left))
            val smallerWidth = min(previous.width(), candidate.width()).coerceAtLeast(1)
            val aligned = kotlin.math.abs(previous.left - candidate.left) <= tallHeight * 1.6f
            return overlap >= smallerWidth * 0.48f && aligned
        }

        fun add(line: TextElement) {
            parts.add(line)
            bounds.union(line.boundingBox!!)
        }

        fun asElement(): TextElement {
            val first = parts.first()
            val paragraph = parts.joinToString(" ") { it.originalText.trim() }
            return first.copy(
                id = language + ":" + paragraph.lowercase(),
                originalText = paragraph,
                boundingBox = Rect(bounds)
            )
        }
    }

    val paragraphs = mutableListOf<Paragraph>()
    for (line in lines) {
        if (line.boundingBox == null) continue
        val paragraph = paragraphs.asReversed().firstOrNull { it.canAppend(line) }
        if (paragraph != null) paragraph.add(line) else paragraphs.add(Paragraph(line))
    }
    return paragraphs.map { it.asElement() }
        .sortedWith(compareBy({ it.boundingBox?.top ?: Int.MAX_VALUE }, { it.boundingBox?.left ?: 0 }))
        .take(14)
}
