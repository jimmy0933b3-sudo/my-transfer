package com.example.cameratranslator.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.cameratranslator.core.PhotoTranslationExport
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.cameratranslator.camera.CameraPreviewScreen
import com.example.cameratranslator.core.MainViewModel
import com.example.cameratranslator.overlay.TranslationOverlay

private data class LanguageChoice(val code: String, val title: String, val enabled: Boolean = true)

private val sources = listOf(
    LanguageChoice("auto", "自動"),
    LanguageChoice("en", "英文"),
    LanguageChoice("ja", "日文"),
    LanguageChoice("zh", "中文"),
    LanguageChoice("th", "泰文（拍照翻譯）")
)

private val targets = listOf(
    LanguageChoice("zh", "繁中"),
    LanguageChoice("en", "英文"),
    LanguageChoice("ja", "日文"),
    LanguageChoice("th", "泰文")
)

@Composable
private fun LanguageMenu(
    caption: String,
    current: String,
    choices: List<LanguageChoice>,
    onChosen: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.code == current }?.title ?: current
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Black.copy(alpha = 0.65f),
                contentColor = Color.White
            )
        ) { Text("$caption：$selected ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.title) },
                    enabled = choice.enabled,
                    onClick = {
                        expanded = false
                        onChosen(choice.code)
                    }
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToPicker: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val textElements by viewModel.textElements.collectAsState()
    val status by viewModel.statusMessage.collectAsState()
    val actualEngine by viewModel.translationEngine.collectAsState()
    val snapshotStatus by viewModel.snapshotStatus.collectAsState()
    val snapshotLines by viewModel.snapshotLines.collectAsState()
    val originalPhoto by viewModel.snapshotOriginalPhoto.collectAsState()
    val translatedPhoto by viewModel.snapshotTranslatedPhoto.collectAsState()
    var showCapturedOriginal by remember { mutableStateOf(false) }
    var showPhotoDetails by remember { mutableStateOf(false) }
    var editOcrIndex by remember { mutableStateOf<Int?>(null) }
    var editOcrText by remember { mutableStateOf("") }
    var editTranslationText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var paused by remember { mutableStateOf(viewModel.isPaused) }
    var showOriginal by remember { mutableStateOf(viewModel.showOriginal) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        CameraPreviewScreen(viewModel)
        TranslationOverlay(
            textElements = textElements,
            showOriginal = showOriginal,
            paused = paused,
            opacity = viewModel.overlayOpacity,
            onCopy = { selectedText ->
                clipboard.setText(AnnotatedString(selectedText))
                Toast.makeText(context, "已複製文字", Toast.LENGTH_SHORT).show()
            }
        )
        if (viewModel.sourceLanguage == "th" && snapshotStatus == null) {
            Text(
                text = "泰文請按「拍照精準翻譯」，首次需下載辨識模型",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center)
                    .background(Color(0xD0183035), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            )
        }
        if (showOriginal && snapshotStatus == null) {
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 88.dp)
                    .heightIn(max = 345.dp)
                    .background(Color(0xF018303C), RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "原文 OCR（未經翻譯）",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "請對準清晰文字；按「暫停」後點句子即可複製。",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall
                )
                if (textElements.isEmpty()) {
                    Text("辨識中，請保持鏡頭穩定。", color = Color.White)
                }
                textElements.sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }.forEach { element ->
                    Text(
                        text = element.originalText,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.11f), RoundedCornerShape(6.dp))
                            .clickable(enabled = paused) {
                                clipboard.setText(AnnotatedString(element.originalText))
                                Toast.makeText(context, "已複製原文", Toast.LENGTH_SHORT).show()
                            }
                            .padding(8.dp)
                    )
                }
            }
        }
        Column(
            Modifier.align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.38f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LanguageMenu("來源", viewModel.sourceLanguage, sources, viewModel::updateSourceLanguage)
                Text("→", color = Color.White)
                LanguageMenu("目標", viewModel.targetLanguage, targets, viewModel::updateTargetLanguage)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (actualEngine != null) {
                    Text(
                        text = "實際：$actualEngine",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(Color(0xFF163F42), RoundedCornerShape(8.dp))
                            .padding(horizontal = 7.dp, vertical = 5.dp)
                    )
                    Spacer(Modifier.weight(1f))
                }
                Button(onClick = viewModel::toggleFlashlight, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text("手電筒")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onNavigateToSettings, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text("設定")
                }
            }
            Text(
                text = "OCR：${textElements.size} 段 ｜雲端／離線譯文：${textElements.count { it.translationEngine != null }} 段",
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.labelSmall
            )
        }
        if (status != null && !paused && snapshotStatus == null) {
            Text(
                status.orEmpty(),
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = 136.dp, start = 18.dp, end = 18.dp)
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            )
        }
        if (paused) {
            Text(
                "已暫停：點選翻譯框即可複製文字",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 88.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        if (snapshotStatus == null) {
            Button(
                onClick = { viewModel.captureSnapshot(context) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 94.dp)
            ) { Text("拍照精準翻譯") }
        }
        if (snapshotStatus == null) Row(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onNavigateToPicker) { Text("相簿") }
            Button(onClick = {
                paused = !paused
                viewModel.isPaused = paused
            }) { Text(if (paused) "恢復" else "暫停") }
            Button(onClick = {
                showOriginal = !showOriginal
                viewModel.showOriginal = showOriginal
            }) { Text(if (showOriginal) "返回翻譯" else "查看原文") }
        }
        // Full-screen still photo is drawn ABOVE CameraX preview and live OCR.
        // Text is already rendered into translatedPhoto's pixels, not placed
        // as floating Compose rectangles.
        if (snapshotStatus != null) {
            val image = if (showCapturedOriginal) originalPhoto
                else translatedPhoto ?: originalPhoto
            Column(
                modifier = Modifier.fillMaxSize()
                    .background(Color(0xFF0C171A))
                    .padding(top = 26.dp, start = 10.dp, end = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("拍照翻譯成品", color = Color.White,
                        style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        showPhotoDetails = false
                        showCapturedOriginal = false
                        viewModel.closeSnapshot()
                    }) { Text("重新拍照") }
                }
                Text(
                    snapshotStatus.orEmpty(),
                    color = Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.bodySmall
                )
                if (image != null) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = if (showCapturedOriginal) "原始拍攝照片" else "譯文直接替換於照片原文字位置",
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        enabled = originalPhoto != null,
                        onClick = { showCapturedOriginal = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (showCapturedOriginal)
                                Color(0xFF006D68) else Color(0xFF6854A5)
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("查看原圖") }
                    Button(
                        enabled = translatedPhoto != null,
                        onClick = { showCapturedOriginal = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!showCapturedOriginal)
                                Color(0xFF006D68) else Color(0xFF6854A5)
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("查看翻譯圖") }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        enabled = translatedPhoto != null,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val toSave = translatedPhoto ?: return@Button
                            scope.launch(Dispatchers.IO) {
                                val uri = PhotoTranslationExport.saveToGallery(context, toSave)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        if (uri != null) "已儲存翻譯照片到相簿"
                                        else "儲存失敗（Android 9 以下請使用分享圖片）",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    ) { Text("儲存翻譯照片") }
                    OutlinedButton(
                        enabled = translatedPhoto != null,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            try {
                                translatedPhoto?.let { PhotoTranslationExport.share(context, it) }
                            } catch (e: Exception) {
                                Toast.makeText(context, "分享照片失敗", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) { Text("分享圖片", color = Color.White) }
                }
                TextButton(onClick = { showPhotoDetails = !showPhotoDetails }) {
                    Text(if (showPhotoDetails) "收合 OCR 明細" else "查看／修正 OCR 原文及譯文",
                        color = Color.White)
                }
                if (showPhotoDetails) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(max = 230.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        snapshotLines.forEachIndexed { i, line ->
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(Color(0xFF22363A), RoundedCornerShape(8.dp))
                                    .clickable {
                                        editOcrIndex = i
                                        editOcrText = line.original
                                        editTranslationText = line.translated.orEmpty()
                                    }
                                    .padding(10.dp)
                            ) {
                                Text((i+1).toString() + ". 原文：" + line.original,
                                    color = Color.White)
                                Text(line.provider + "：" + (line.translated ?: "尚未完成"),
                                    color = Color(0xFFB0E1CB))
                            }
                        }
                    }
                }
            }
        }
        if (editOcrIndex != null) {
            AlertDialog(
                onDismissRequest = { editOcrIndex = null },
                title = { Text("修正 OCR 錯字") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "先校正相機辨識的原文。修改原文會重新翻譯並更新照片上的文字。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = editOcrText,
                            onValueChange = { editOcrText = it },
                            label = { Text("OCR 原文（可修正）") },
                            maxLines = 5
                        )
                        OutlinedTextField(
                            value = editTranslationText,
                            onValueChange = { editTranslationText = it },
                            label = { Text("譯文（只改譯文可直接套用）") },
                            maxLines = 5
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        editOcrIndex?.let { index ->
                            val old = snapshotLines.getOrNull(index)
                            if (old != null) {
                                viewModel.correctSnapshotOcr(
                                    index, editOcrText,
                                    if (editOcrText.trim() == old.original.trim())
                                        editTranslationText else null
                                )
                            }
                        }
                        editOcrIndex = null
                        showCapturedOriginal = false
                    }) { Text("重新翻譯並更新照片") }
                },
                dismissButton = {
                    TextButton(onClick = { editOcrIndex = null }) { Text("取消") }
                }
            )
        }

    }
}
