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
                    viewModel.onCameraTextElements(groupParagraphs(mergeLines(en, zh, ja)))
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

private fun extractLines(result: Text, selectedSource: String, script: String): List<TextElement> =
    result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
        val value = line.text.trim()
        val box = line.boundingBox ?: return@mapNotNull null
        if (box.isEmpty || value.length < 2 || value.none { it.isLetter() }) return@mapNotNull null
        val language = if (selectedSource != "auto") selectedSource else when {
            kanaCharacters.containsMatchIn(value) -> "ja"
            hanCharacters.containsMatchIn(value) -> "zh"
            else -> "en"
        }
        if (selectedSource == "auto") {
            val belongs = when (script) {
                "ja" -> kanaCharacters.containsMatchIn(value)
                "zh" -> hanCharacters.containsMatchIn(value)
                else -> latinCharacters.containsMatchIn(value) &&
                    !kanaCharacters.containsMatchIn(value) && !hanCharacters.containsMatchIn(value)
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

/** Japanese wins on kana text, Chinese on Han-only, Latin on ordinary English. */
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
            val smallerArea = min(box.width().toLong() * box.height(), old.width().toLong() * old.height())
            smallerArea > 0 && (w.toLong() * h).toFloat() / smallerArea > 0.55f
        }
        if (index == -1) output.add(candidate)
        else if (candidate.sourceLanguage == "ja" ||
            (candidate.sourceLanguage == "zh" && output[index].sourceLanguage == "en")
        ) output[index] = candidate
    }
    return output.sortedWith(compareBy({ it.boundingBox?.top ?: Int.MAX_VALUE }, { it.boundingBox?.left ?: 0 })).take(64)
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
            if (next.sourceLanguage != language || parts.size >= 8) return false
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
            return overlap >= smallerWidth * 0.35f && aligned
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
        .take(24)
}
