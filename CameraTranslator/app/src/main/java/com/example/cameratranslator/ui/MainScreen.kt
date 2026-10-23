package com.example.cameratranslator.ui

import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
    val snapshotOriginal by viewModel.snapshotOriginal.collectAsState()
    val snapshotTranslated by viewModel.snapshotTranslated.collectAsState()
    var showPhotoOriginal by remember { mutableStateOf(false) }
    var editingLine by remember { mutableStateOf<Int?>(null) }
    var correctedText by remember { mutableStateOf("") }
    var correctedOriginal by remember { mutableStateOf("") }
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
        if (snapshotStatus != null) {
            Column(
                Modifier.fillMaxSize()
                    .padding(top = 150.dp, bottom = 12.dp, start = 8.dp, end = 8.dp)
                    .background(Color(0xF60F1A23), RoundedCornerShape(12.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("照片原位置翻譯", color = Color.White,
                        style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = viewModel::closeSnapshot) {
                        Text("返回相機")
                    }
                }
                Text(snapshotStatus.orEmpty(), color = Color.White,
                    style = MaterialTheme.typography.labelSmall)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { showPhotoOriginal = !showPhotoOriginal },
                        contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text(if (showPhotoOriginal) "顯示翻譯圖" else "查看原圖")
                    }
                    Button(
                        onClick = { viewModel.saveTranslatedSnapshot(context) },
                        enabled = snapshotTranslated != null,
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) { Text("儲存翻譯照片") }
                }
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .background(Color.Black, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val shown = if (showPhotoOriginal || snapshotTranslated == null)
                        snapshotOriginal else snapshotTranslated
                    if (shown != null && !shown.isRecycled) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = if (showPhotoOriginal) "原始拍攝照片"
                                else "文字已覆蓋至原位置的翻譯照片",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text("正在拍攝並辨識照片…", color = Color.White)
                    }
                }
                Text(
                    "OCR 原文與譯文（點選可修正譯文，照片會重新生成）",
                    color = Color.White, style = MaterialTheme.typography.labelMedium
                )
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 178.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    snapshotLines.forEachIndexed { index, line ->
                        Column(
                            Modifier.fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.10f),
                                    RoundedCornerShape(7.dp))
                                .clickable(enabled = !line.translated.isNullOrBlank()) {
                                    editingLine = index
                                    correctedText = line.translated.orEmpty()
                                    correctedOriginal = line.original
                                }
                                .padding(horizontal = 9.dp, vertical = 6.dp)
                        ) {
                            Text((index + 1).toString() + ". OCR [" + line.sourceLanguage +
                                "] " + line.original,
                                color = Color(0xFFC5DEEE),
                                style = MaterialTheme.typography.bodySmall)
                            Text(
                                line.translated ?: "待翻譯／未取得譯文",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(line.provider, color = Color(0xFF9FDAB8),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        if (editingLine != null) {
            AlertDialog(
                onDismissRequest = { editingLine = null },
                title = { Text("校正 OCR 與譯文") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = correctedOriginal,
                            onValueChange = { correctedOriginal = it },
                            label = { Text("OCR 原文（辨識錯字請在這裡改）") },
                            maxLines = 5
                        )
                        OutlinedTextField(
                            value = correctedText,
                            onValueChange = { correctedText = it },
                            label = { Text("修正後的譯文") },
                            maxLines = 6
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        editingLine?.let { index ->
                            val old = snapshotLines.getOrNull(index)
                            if (old != null) {
                                if (correctedOriginal.trim() != old.original.trim()) {
                                    viewModel.correctSnapshotOcr(index, correctedOriginal)
                                } else {
                                    viewModel.updateSnapshotTranslation(index, correctedText)
                                }
                            }
                        }
                        editingLine = null
                        showPhotoOriginal = false
                    }) { Text("修正並更新照片") }
                },
                dismissButton = {
                    TextButton(onClick = { editingLine = null }) { Text("取消") }
                }
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
        if (paused && snapshotStatus == null) {
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
    }
}
