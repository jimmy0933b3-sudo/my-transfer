package com.example.cameratranslator.ui

import android.widget.Toast
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
import com.example.cameratranslator.core.SnapshotOcrClipboard
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
    var showCapturedOriginal by remember { mutableStateOf(true) }
    var showPhotoDetails by remember { mutableStateOf(false) }
    var editOcrIndex by remember { mutableStateOf<Int?>(null) }
    var editOcrText by remember { mutableStateOf("") }
    var editTranslationText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        CameraPreviewScreen(viewModel)
        TranslationOverlay(
            textElements = textElements,
            showOriginal = false,
            paused = false,
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
        if (status != null && snapshotStatus == null) {
            Text(
                status.orEmpty(),
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = 136.dp, start = 18.dp, end = 18.dp)
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            )
        }
        if (snapshotStatus == null) {
            Button(
                onClick = { viewModel.captureSnapshot(context) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 94.dp)
            ) { Text("拍照精準翻譯") }
        }
        if (snapshotStatus == null) {
            Button(
                onClick = onNavigateToPicker,
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = 18.dp, bottom = 20.dp)
            ) { Text("相簿") }
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
                    Text("拍照結果", color = Color.White,
                        style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        showPhotoDetails = false
                        showCapturedOriginal = true
                        viewModel.closeSnapshot()
                    }) { Text("重新拍照") }
                }
                Text(
                    snapshotStatus.orEmpty(),
                    color = Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.bodySmall
                )
                if (image != null) {
                    ZoomablePhoto(
                        bitmap = image,
                        contentDescription = if (showCapturedOriginal)
                            "原始拍攝照片，可雙指縮放" else "照片原位置翻譯結果，可雙指縮放",
                        modifier = Modifier.fillMaxWidth().weight(1f)
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
                        onClick = {
                            showCapturedOriginal = true
                            showPhotoDetails = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (showCapturedOriginal)
                                Color(0xFF006D68) else Color(0xFF6854A5)
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("查看原圖") }
                    Button(
                        enabled = translatedPhoto != null,
                        onClick = {
                            showCapturedOriginal = false
                            showPhotoDetails = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!showCapturedOriginal)
                                Color(0xFF006D68) else Color(0xFF6854A5)
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("查看翻譯圖") }
                }
                if (showCapturedOriginal) {
                    val fullOriginal = SnapshotOcrClipboard.all(snapshotLines)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            enabled = fullOriginal.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                clipboard.setText(AnnotatedString(fullOriginal))
                                Toast.makeText(context, "已複製全部 OCR 原文", Toast.LENGTH_SHORT).show()
                            }
                        ) { Text("複製全部原文") }
                        OutlinedButton(
                            enabled = snapshotLines.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            onClick = { showPhotoDetails = !showPhotoDetails }
                        ) {
                            Text(
                                if (showPhotoDetails) "收合原文" else "逐段複製 OCR",
                                color = Color.White
                            )
                        }
                    }
                    if (snapshotLines.isEmpty()) {
                        Text(
                            "照片正在辨識文字，完成後即可複製 OCR 原文。",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
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
                }
                TextButton(
                    onClick = { showPhotoDetails = !showPhotoDetails }
                ) {
                    Text(
                        if (showPhotoDetails) "收合 OCR 修正" else "查看／修正 OCR 原文及譯文",
                        color = Color.White
                    )
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
                                        if (showCapturedOriginal) {
                                            clipboard.setText(AnnotatedString(line.original))
                                            Toast.makeText(
                                                context,
                                                "已複製第 " + (i + 1) + " 段原文",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            editOcrIndex = i
                                            editOcrText = line.original
                                            editTranslationText = line.translated.orEmpty()
                                        }
                                    }
                                    .padding(10.dp)
                            ) {
                                Text(
                                    (i + 1).toString() + ". 原文：" + line.original,
                                    color = Color.White
                                )
                                Text(
                                    if (showCapturedOriginal) "點這段可直接複製"
                                    else line.provider + "：" + (line.translated ?: "尚未完成"),
                                    color = Color(0xFFB0E1CB),
                                    style = MaterialTheme.typography.bodySmall
                                )
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
