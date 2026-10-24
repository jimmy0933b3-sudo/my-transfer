package com.example.cameratranslator.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.cameratranslator.core.MainViewModel
import kotlin.math.max

/**
 * Gallery is another still-photo input, not a live OCR/overlay screen.
 * Reuse exactly the same OCR -> Azure/offline -> image compositor pipeline
 * as the camera shutter, then show the common photo results page.
 */
@Composable
fun ImagePickerScreen(viewModel: MainViewModel, onReturnToCamera: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var decoding by remember { mutableStateOf(false) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !decoding) {
            decoding = true
            scope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        decodeGalleryImage(context, uri)
                    } ?: error("無法讀取所選照片")
                    viewModel.translateSelectedPhoto(bitmap)
                    onReturnToCamera()
                } catch (e: Exception) {
                    Toast.makeText(context, "照片讀取失敗，請選擇其他照片", Toast.LENGTH_SHORT).show()
                } finally {
                    decoding = false
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TextButton(onClick = onReturnToCamera) { Text("← 返回相機") }
        Text("相簿照片翻譯", style = MaterialTheme.typography.headlineMedium)
        Text("選擇照片後才辨識與翻譯，譯文會直接畫回照片上的文字位置。可切換原圖、儲存或分享。")
        Button(onClick = {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }, enabled = !decoding, modifier = Modifier.fillMaxWidth()) {
            Text(if (decoding) "正在讀取照片…" else "選擇照片並翻譯")
        }
        if (decoding) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

private fun decodeGalleryImage(context: android.content.Context, uri: Uri): Bitmap? {
    if (Build.VERSION.SDK_INT >= 28) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longer = max(info.size.width, info.size.height)
            var sample = 1
            while (longer / sample > 3000) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    }
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    var sample = 1
    while (max(options.outWidth, options.outHeight) / sample > 3000) sample *= 2
    val config = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, config)
    }
}
