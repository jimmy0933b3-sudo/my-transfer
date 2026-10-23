package com.example.cameratranslator.ui

import android.widget.Toast
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
    LanguageChoice("th", "泰文")
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
        if (snapshotStatus != null) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxSize()
                    .padding(top = 242.dp, bottom = 16.dp, start = 10.dp, end = 10.dp)
                    .background(Color(0xF50C2026), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("高解析度拍照翻譯", color = Color.White,
                        style = MaterialTheme.typography.titleMedium)
                    Button(onClick = viewModel::closeSnapshot) { Text("返回相機") }
                }
                Text(snapshotStatus.orEmpty(), color = Color.White,
                    style = MaterialTheme.typography.bodySmall)
                Text("支援英文／日文／中文／泰文（泰文先到設定下載 OCR 模型）。請先確認 OCR 原文。",
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodySmall)
                Column(
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    snapshotLines.forEachIndexed { i, line ->
                        Column(
                            Modifier.fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.09f),
                                    RoundedCornerShape(9.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text((i + 1).toString() + ". OCR " +
                                    com.example.cameratranslator.core.SnapshotTextPolicy.displayLanguage(
                                        line.sourceLanguage
                                    ) + "原文",
                                color = Color(0xFFAACFE6),
                                style = MaterialTheme.typography.labelMedium)
                            Text(line.original, color = Color.White,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(line.provider, color = Color(0xFF8FD5AC),
                                style = MaterialTheme.typography.labelSmall)
                            Text(line.translated ?: "尚未完成翻譯",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium)
                            TextButton(
                                enabled = !line.translated.isNullOrBlank(),
                                onClick = {
                                    clipboard.setText(AnnotatedString(line.translated.orEmpty()))
                                    Toast.makeText(context, "已複製譯文", Toast.LENGTH_SHORT).show()
                                }
                            ) { Text("複製譯文") }
                        }
                    }
                }
            }
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
        if (viewModel.sourceLanguage == "th" && snapshotStatus == null) {
            Text(
                "泰文請按「拍照翻譯」；先在設定下載泰文 OCR 模型。",
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = 150.dp, start = 18.dp, end = 18.dp)
                    .background(Color(0xD0102026), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            )
        }
        if (status != null && !paused && snapshotStatus == null &&
            viewModel.sourceLanguage != "th") {
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
            ) { Text("拍照翻譯（多語言）") }
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
