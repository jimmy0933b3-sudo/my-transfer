package com.example.cameratranslator.camera

import android.util.Log
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
import com.example.cameratranslator.core.MainViewModel

/**
 * Camera preview is deliberately PHOTO-ONLY.
 *
 * There is no ImageAnalysis use case, OCR recognizer, analyzer, or per-frame
 * translation. ImageCapture saves a real still JPEG after the user taps shutter.
 * OCR and translation run once, using the full-resolution still bitmap.
 */
@Composable
fun CameraPreviewScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val controller = remember(context) {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
    }
    val preview = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            this.controller = controller
        }
    }

    DisposableEffect(controller, owner, viewModel) {
        viewModel.cameraController = controller
        try {
            controller.bindToLifecycle(owner)
        } catch (error: Exception) {
            Log.e("CameraTranslator", "Camera preview failed", error)
            viewModel.showCameraError()
        }
        onDispose {
            controller.unbind()
            if (viewModel.cameraController === controller) {
                viewModel.cameraController = null
            }
        }
    }

    AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
}
