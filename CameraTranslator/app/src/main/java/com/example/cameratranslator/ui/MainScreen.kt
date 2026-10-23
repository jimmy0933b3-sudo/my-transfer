package com.example.cameratranslator.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cameratranslator.camera.CameraPreviewScreen
import com.example.cameratranslator.core.MainViewModel
import com.example.cameratranslator.core.PhotoTranslationExport
import com.example.cameratranslator.core.SnapshotOcrClipboard
import com.example.cameratranslator.core.SnapshotTranslationLine
import com.example.cameratranslator.overlay.TranslationOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Night = Color(0xFF0E1822)
private val SurfaceNight = Color(0xFF172633)
private val Mint = Color(0xFF73E3CA)
private val Muted = Color(0xFFA8B9C6)
private val Mist = Color(0xFFF3F8FA)

private data class LanguageChoice(val code: String, val title: String)
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
    code: String,
    caption: String,
    choices: List<LanguageChoice>,
    onChosen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.code == code }?.title ?: code
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(caption, color = Color.White.copy(alpha = 0.67f), fontSize = 11.sp)
            Text(selected, color = Color.White, fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Text("⌄", color = Mint, fontSize = 18.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.title) },
                    onClick = {
                        expanded = false
                        onChosen(choice.code)
                    },
                    trailingIcon = if (choice.code == code) {
                        { Text("✓", color = Color(0xFF08796C)) }
                    } else null
                )
            }
        }
    }
}

@Composable
private fun CameraShutter(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(CircleShape)
                .border(3.dp, Color.White, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(60.dp)
                    .clip(CircleShape)
                    .background(Mist)
                    .border(2.dp, Night.copy(alpha = 0.22f), CircleShape)
            )
        }
        Text("拍照翻譯", color = Color.White,
            fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}

@Composable
private fun RowScope.PhotoAction(
    glyph: String,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(glyph, fontSize = 22.sp,
            color = if (enabled) Mint else Muted.copy(alpha = 0.4f))
        Text(label, fontSize = 12.sp,
            color = if (enabled) Color.White else Muted.copy(alpha = 0.5f))
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

    // A new captured image starts with its original. Show translated image
    // automatically when the composite is ready, while retaining manual toggle.
    LaunchedEffect(originalPhoto) {
        if (originalPhoto != null) {
            showCapturedOriginal = true
            showPhotoDetails = false
        }
    }
    LaunchedEffect(translatedPhoto) {
        if (translatedPhoto != null) showCapturedOriginal = false
    }
    BackHandler(enabled = snapshotStatus != null) {
        showPhotoDetails = false
        viewModel.closeSnapshot()
    }

    Box(Modifier.fillMaxSize().background(Night)) {
        // Keep CameraX bound during photo capture. Unmounting camera here
        // can interrupt takePicture before the JPEG has finished saving.
        CameraPreviewScreen(viewModel)
        if (snapshotStatus == null) {
            TranslationOverlay(
                textElements = textElements,
                showOriginal = false,
                paused = false,
                opacity = viewModel.overlayOpacity,
                onCopy = { value ->
                    clipboard.setText(AnnotatedString(value))
                    Toast.makeText(context, "已複製翻譯", Toast.LENGTH_SHORT).show()
                }
            )

            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(
                        listOf(Color(0xD9001019), Color(0xAA001019), Color.Transparent)
                    ))
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 23.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LanguageMenu(viewModel.sourceLanguage, "來源", sources,
                        viewModel::updateSourceLanguage, Modifier.weight(1f))
                    Text("→", color = Mint, fontSize = 20.sp)
                    LanguageMenu(viewModel.targetLanguage, "目標", targets,
                        viewModel::updateTargetLanguage, Modifier.weight(1f))
                    Box(
                        Modifier.size(42.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.13f))
                            .clickable(onClick = onNavigateToSettings),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⚙", color = Color.White, fontSize = 22.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape)
                        .background(if (actualEngine != null) Mint else Color(0xFFFFD38C)))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        actualEngine ?: "準備辨識 · 對準文字",
                        color = Color.White.copy(alpha = 0.87f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (viewModel.sourceLanguage == "th") "泰文使用拍照辨識"
                        else "已辨識 " + textElements.size + " 段",
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xCC051018), Color(0xF4051018))
                    ))
                    .padding(top = 36.dp, start = 22.dp, end = 22.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (status != null && status != "請對準英文、日文或中文字") {
                    Text(
                        status.orEmpty(),
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(bottom = 13.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xCC243845))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier.width(92.dp).clickable(onClick = onNavigateToPicker)
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(Modifier.size(47.dp).clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center) {
                            Text("▣", fontSize = 25.sp, color = Color.White)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("相簿", color = Color.White, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    CameraShutter(onClick = { viewModel.captureSnapshot(context) })
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.width(92.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(47.dp).clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center) {
                            Text("文", fontSize = 22.sp, color = Mint)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("原位替字", color = Color.White.copy(alpha = 0.76f),
                            fontSize = 12.sp)
                    }
                }
            }
        }

        if (snapshotStatus != null) {
            Column(
                modifier = Modifier.fillMaxSize().background(Night),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        showPhotoDetails = false
                        viewModel.closeSnapshot()
                    }) { Text("‹ 返回", color = Color.White, fontSize = 16.sp) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("照片翻譯", fontWeight = FontWeight.SemiBold,
                            color = Color.White, fontSize = 17.sp)
                        Text(
                            if (translatedPhoto != null) "翻譯已完成"
                            else if (originalPhoto != null) "正在處理照片"
                            else "拍攝中",
                            color = Mint, fontSize = 11.sp
                        )
                    }
                    TextButton(onClick = {
                        showPhotoDetails = false
                        viewModel.closeSnapshot()
                    }) { Text("重拍", color = Mint, fontSize = 14.sp) }
                }

                Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF070E14))) {
                    val image = if (showCapturedOriginal) originalPhoto
                        else translatedPhoto ?: originalPhoto
                    if (image != null) {
                        ZoomablePhoto(
                            bitmap = image,
                            contentDescription = if (showCapturedOriginal)
                                "原始照片，支援雙指縮放" else "已將譯文繪製於原字位置的照片",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = Mint
                        )
                    }
                }

                Column(
                    Modifier.fillMaxWidth()
                        .background(SurfaceNight)
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(true to "原圖", false to "翻譯圖").forEach { (original, text) ->
                            val selected = showCapturedOriginal == original
                            val enabled = if (original) originalPhoto != null
                                else translatedPhoto != null
                            Box(
                                Modifier.weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) Mint else Color(0xFF263B46))
                                    .clickable(enabled = enabled) {
                                        showCapturedOriginal = original
                                        showPhotoDetails = false
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (!enabled) Muted.copy(alpha = 0.5f)
                                        else if (selected) Night else Color.White,
                                    fontSize = 14.sp)
                            }
                        }
                    }

                    // One short progress line, not a large banner that pushes the
                    // photo out of view. Empty OCR still shows the full captured image.
                    Text(
                        snapshotStatus.orEmpty(),
                        color = Muted,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 15.sp
                    )

                    val originalOCR = SnapshotOcrClipboard.all(snapshotLines)
                    val translatedText = snapshotLines.mapNotNull { it.translated }
                        .filter { it.isNotBlank() }.joinToString("\n")
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        PhotoAction("▤", "複製文字",
                            enabled = if (showCapturedOriginal) originalOCR.isNotBlank()
                            else translatedText.isNotBlank()) {
                            val selectedText = if (showCapturedOriginal) originalOCR else translatedText
                            clipboard.setText(AnnotatedString(selectedText))
                            Toast.makeText(context,
                                if (showCapturedOriginal) "已複製 OCR 原文" else "已複製譯文",
                                Toast.LENGTH_SHORT).show()
                        }
                        PhotoAction("↓", "儲存",
                            enabled = translatedPhoto != null) {
                            val photo = translatedPhoto
                            if (photo != null) scope.launch(Dispatchers.IO) {
                                val uri = PhotoTranslationExport.saveToGallery(context, photo)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context,
                                        if (uri != null) "已存入相簿" else "儲存失敗",
                                        Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        PhotoAction("↗", "分享",
                            enabled = translatedPhoto != null) {
                            try {
                                translatedPhoto?.let { PhotoTranslationExport.share(context, it) }
                            } catch (_: Exception) {
                                Toast.makeText(context, "分享失敗", Toast.LENGTH_SHORT).show()
                            }
                        }
                        PhotoAction("✎", "文字校正",
                            enabled = snapshotLines.isNotEmpty()) {
                            showPhotoDetails = !showPhotoDetails
                        }
                    }
                    if (showPhotoDetails) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
                        Text(
                            if (showCapturedOriginal)
                                "辨識原文 · 點選可複製單段文字"
                            else "翻譯內容 · 點選可修正原文與譯文",
                            color = Mint, fontSize = 12.sp
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(max = 205.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            snapshotLines.forEachIndexed { index, line ->
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF233743))
                                        .clickable {
                                            if (showCapturedOriginal) {
                                                clipboard.setText(AnnotatedString(line.original))
                                                Toast.makeText(context,
                                                    "已複製第 " + (index + 1) + " 段",
                                                    Toast.LENGTH_SHORT).show()
                                            } else {
                                                editOcrIndex = index
                                                editOcrText = line.original
                                                editTranslationText = line.translated.orEmpty()
                                            }
                                        }.padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text((index + 1).toString() + ". " + line.original,
                                        color = Color.White, fontSize = 13.sp,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (showCapturedOriginal) "點按複製"
                                        else line.provider + " · " + (line.translated ?: "尚未完成"),
                                        color = Muted, fontSize = 12.sp,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (editOcrIndex != null) {
        AlertDialog(
            onDismissRequest = { editOcrIndex = null },
            title = { Text("修正文字辨識") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("修正原文後會重新翻譯並更新照片。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = editOcrText,
                        onValueChange = { editOcrText = it },
                        label = { Text("OCR 原文") }, maxLines = 5)
                    OutlinedTextField(value = editTranslationText,
                        onValueChange = { editTranslationText = it },
                        label = { Text("譯文") }, maxLines = 5)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editOcrIndex?.let { index ->
                        val oldLine = snapshotLines.getOrNull(index)
                        if (oldLine != null) {
                            viewModel.correctSnapshotOcr(
                                index, editOcrText,
                                if (editOcrText.trim() == oldLine.original.trim())
                                    editTranslationText else null
                            )
                        }
                    }
                    editOcrIndex = null
                    showCapturedOriginal = false
                }) { Text("套用並更新圖片") }
            },
            dismissButton = {
                TextButton(onClick = { editOcrIndex = null }) { Text("取消") }
            }
        )
    }
}
