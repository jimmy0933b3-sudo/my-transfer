package com.example.cameratranslator.camera

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
    val latin = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val japanese = remember { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }

    DisposableEffect(controller, owner, viewModel) {
        viewModel.cameraController = controller
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(
                listOf(latin, japanese),
                CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                executor
            ) { result ->
                if (!viewModel.isPaused) {
                    result.getThrowable(latin)?.let { Log.w("CameraTranslator", "Latin OCR error", it) }
                    result.getThrowable(japanese)?.let { Log.w("CameraTranslator", "Japanese OCR error", it) }
                    val en = result.getValue(latin)?.let { extractLines(it, "en", false) }.orEmpty()
                    val ja = result.getValue(japanese)?.let { extractLines(it, "ja", true) }.orEmpty()
                    viewModel.onCameraTextElements(mergeLines(en, ja))
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
            latin.close()
            japanese.close()
        }
    }
    AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
}

private val japaneseCharacters = Regex("[\u3040-\u30ff\u3400-\u9fff]")

private fun extractLines(result: Text, fallbackLanguage: String, japaneseOnly: Boolean): List<TextElement> =
    result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
        val value = line.text.trim()
        val box = line.boundingBox ?: return@mapNotNull null
        if (box.isEmpty || value.length < 2 || value.none { it.isLetter() }) return@mapNotNull null
        if (japaneseOnly && !japaneseCharacters.containsMatchIn(value)) return@mapNotNull null
        val language = if (japaneseCharacters.containsMatchIn(value)) "ja" else fallbackLanguage
        TextElement(
            id = language + ":" + value.lowercase(),
            originalText = value,
            boundingBox = box,
            sourceLanguage = language,
            isViewCoordinates = true
        )
    }

private fun mergeLines(latin: List<TextElement>, japanese: List<TextElement>): List<TextElement> {
    val output = latin.toMutableList()
    for (candidate in japanese) {
        val box = candidate.boundingBox ?: continue
        val overlapIndex = output.indexOfFirst { existing ->
            val old = existing.boundingBox ?: return@indexOfFirst false
            val w = max(0, min(box.right, old.right) - max(box.left, old.left))
            val h = max(0, min(box.bottom, old.bottom) - max(box.top, old.top))
            val smallerArea = min(box.width() * box.height(), old.width() * old.height())
            smallerArea > 0 && w.toFloat() * h / smallerArea > 0.55f
        }
        if (overlapIndex == -1) output.add(candidate)
        else if (candidate.sourceLanguage == "ja") output[overlapIndex] = candidate
    }
    return output.sortedWith(compareBy({ it.boundingBox?.top ?: Int.MAX_VALUE }, { it.boundingBox?.left ?: 0 })).take(32)
}
