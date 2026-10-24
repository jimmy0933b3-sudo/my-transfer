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
import com.example.cameratranslator.core.PhotoEngineSummary
import com.example.cameratranslator.core.PhotoTranslationExport
import com.example.cameratranslator.core.PhotoSpeechContent
import com.example.cameratranslator.core.SnapshotOcrClipboard
import com.example.cameratranslator.core.SnapshotTranslationLine
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
                .background(Color.White.copy(alpha = 0.10f))
                .border(3.dp, Color.White.copy(alpha = 0.95f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(58.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color.White, Color(0xFFADEADF))))
                    .border(2.dp, Night.copy(alpha = 0.22f), CircleShape)
            )
        }
        Text("拍照翻譯", color = Color.White,
            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(glyph, fontSize = 20.sp,
            color = if (enabled) Mint else Muted.copy(alpha = 0.4f))
        Text(label, fontSize = 11.sp,
            color = if (enabled) Color.White else Muted.copy(alpha = 0.5f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToPicker: () -> Unit,
    onNavigateToSettings: () -> Unit,
    cameraPermissionGranted: Boolean,
    onRequestCameraPermission: () -> Unit
) {
    val snapshotStatus by viewModel.snapshotStatus.collectAsState()
    val snapshotLines by viewModel.snapshotLines.collectAsState()
    val photoEngineSummary = PhotoEngineSummary.describe(snapshotLines.map { it.provider })
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
    val speech = remember(context) { PhotoSpeechPlayer(context) }
    DisposableEffect(speech) {
        onDispose { speech.shutdown() }
    }
    // Never read stale OCR after a new photo, an edit or a tab change.
    LaunchedEffect(snapshotLines, originalPhoto, showCapturedOriginal) {
        speech.stop()
    }
    LaunchedEffect(speech.errorMessage) {
        speech.errorMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

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
        speech.stop()
        showPhotoDetails = false
        viewModel.closeSnapshot()
    }

    Box(Modifier.fillMaxSize().background(Night)) {
        // Keep CameraX bound during photo capture. Unmounting camera here
        // can interrupt takePicture before the JPEG has finished saving.
        if (cameraPermissionGranted) {
            CameraPreviewScreen(viewModel)
        } else if (snapshotStatus == null) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("尚未授權相機", color = Color.White, fontSize = 18.sp)
                Text("仍可從相簿選擇照片翻譯", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRequestCameraPermission) { Text("開啟相機權限") }
            }
        }
        if (snapshotStatus == null) {

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
                        .background(Mint))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "純拍照翻譯 · 不會在預覽時辨識或上傳文字",
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2
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
                    CameraShutter(onClick = {
                        if (cameraPermissionGranted) viewModel.captureSnapshot(context)
                        else onRequestCameraPermission()
                    })
                    Spacer(Modifier.weight(1f))
                    // Match the album slot to keep the shutter perfectly centered.
                    // The former "文／拍完再翻譯" decoration was not a real action.
                    Spacer(Modifier.width(92.dp))
                }
            }
        }


        // Full-size viewer: controls float at the edges instead of permanently
        // squeezing the photograph into a small card.
        if (snapshotStatus != null) {
            val photo = if (showCapturedOriginal) originalPhoto
                else translatedPhoto ?: originalPhoto
            Box(Modifier.fillMaxSize().background(Night)) {
                if (photo != null) {
                    ZoomablePhoto(
                        bitmap = photo,
                        contentDescription = if (showCapturedOriginal)
                            "原始照片，支援雙指縮放" else "譯文已繪製到原始位置的照片",
                        modifier = Modifier.fillMaxSize()
                            .padding(top = 54.dp, bottom = 146.dp)
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center), color = Mint
                    )
                }

                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Night, Night.copy(alpha = 0.84f))))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        speech.stop()
                        showPhotoDetails = false
                        viewModel.closeSnapshot()
                    }) { Text("‹ 返回", color = Color.White, fontSize = 14.sp) }
                    Text(
                        "照片翻譯", modifier = Modifier.weight(1f),
                        color = Color.White, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    TextButton(
                        onClick = { viewModel.rotateSnapshotClockwise() },
                        enabled = originalPhoto != null && translatedPhoto != null
                    ) { Text("旋轉 ↻", color = Mint, fontSize = 14.sp) }
                }

                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(
                            listOf(Night.copy(alpha = 0.89f), Night)
                        ))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    // Keep OCR diagnostics in the sheet, not in the photo viewport.
                    // The summary is always tappable, even if recognition fails.
                    val recognitionSummary = when {
                        snapshotLines.isNotEmpty() -> "已辨識 ${snapshotLines.size} 段文字"
                        snapshotStatus?.contains("沒有辨識到") == true -> "未辨識到清楚文字"
                        snapshotStatus?.contains("失敗") == true ||
                            snapshotStatus?.contains("無法") == true -> "照片處理失敗"
                        else -> "正在辨識文字…"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = 36.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(Color.White.copy(alpha = 0.09f))
                            .clickable { showPhotoDetails = true }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            recognitionSummary,
                            modifier = Modifier.weight(1f),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text("查看明細 ›", color = Mint, fontSize = 12.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(true to "原圖", false to "翻譯圖").forEach { (original, title) ->
                            val selected = showCapturedOriginal == original
                            val enabled = if (original) originalPhoto != null
                                else translatedPhoto != null
                            Box(
                                Modifier.weight(1f).height(36.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) Mint else Color(0xFF263B46))
                                    .clickable(enabled = enabled) {
                                        showCapturedOriginal = original
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    title, color = if (!enabled) Muted.copy(alpha = 0.5f)
                                        else if (selected) Night else Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    val originalOCR = SnapshotOcrClipboard.all(snapshotLines)
                    val translatedText = snapshotLines.mapNotNull { it.translated }
                        .filter { it.isNotBlank() }.joinToString("\n")
                    val speechPhrases = PhotoSpeechContent.forPhoto(
                        lines = snapshotLines,
                        translated = !showCapturedOriginal,
                        targetLanguage = viewModel.targetLanguage,
                        sourceFallback = viewModel.sourceLanguage
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PhotoAction("▤", "複製",
                            enabled = if (showCapturedOriginal) originalOCR.isNotBlank()
                                else translatedText.isNotBlank()) {
                            val selectedText = if (showCapturedOriginal) originalOCR else translatedText
                            clipboard.setText(AnnotatedString(selectedText))
                            Toast.makeText(context,
                                if (showCapturedOriginal) "已複製 OCR 原文" else "已複製譯文",
                                Toast.LENGTH_SHORT).show()
                        }
                        PhotoAction(
                            if (speech.isSpeaking || speech.isPreparing) "■" else "▶",
                            if (speech.isSpeaking || speech.isPreparing) "停止"
                                else if (showCapturedOriginal) "朗讀原文" else "朗讀譯文",
                            enabled = speechPhrases.isNotEmpty() ||
                                speech.isSpeaking || speech.isPreparing
                        ) {
                            speech.toggle(speechPhrases)
                        }
                        PhotoAction("↓", "儲存", enabled = translatedPhoto != null) {
                            translatedPhoto?.let { image ->
                                scope.launch(Dispatchers.IO) {
                                    val uri = PhotoTranslationExport.saveToGallery(
                                        context, image, viewModel.actualPhotoOcrEngine,
                                        photoEngineSummary
                                    )
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context,
                                            if (uri != null) "已存入相簿" else "儲存失敗",
                                            Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        PhotoAction("↗", "分享", enabled = translatedPhoto != null) {
                            try {
                                translatedPhoto?.let {
                                    PhotoTranslationExport.share(
                                        context, it, viewModel.actualPhotoOcrEngine,
                                        photoEngineSummary
                                    )
                                }
                            } catch (_: Exception) {
                                Toast.makeText(context, "分享失敗", Toast.LENGTH_SHORT).show()
                            }
                        }
                        PhotoAction("✎", "文字明細", enabled = snapshotLines.isNotEmpty()) {
                            showPhotoDetails = true
                        }
                    }
                }
            }
        }
    }

    // Editing and engine diagnostics live in a separate bottom sheet.
    // Opening it never shrinks the original/translated photo viewport.
    if (snapshotStatus != null && showPhotoDetails) {
        ModalBottomSheet(
            onDismissRequest = { showPhotoDetails = false },
            containerColor = SurfaceNight,
            contentColor = Color.White
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 580.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text("辨識與翻譯明細", color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("OCR：" + viewModel.actualPhotoOcrEngine,
                    color = Mint, fontSize = 12.sp)
                Text("翻譯：" + photoEngineSummary,
                    color = Muted, fontSize = 12.sp)
                Text("Paddle： " + viewModel.photoOcrDiagnostics,
                    color = Muted, fontSize = 12.sp)
                Text("處理狀態：" + snapshotStatus.orEmpty(),
                    color = Muted, fontSize = 12.sp)
                Text("同一張照片比對 Paddle v5／v6",
                    color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("不重新拍照、不改預設 OCR 設定；各自重新辨識、翻譯及替字。",
                    color = Muted, fontSize = 11.sp)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("V5" to "用 v5 辨識", "V6" to "用 v6 辨識")
                        .forEach { (versionId, label) ->
                            OutlinedButton(
                                onClick = {
                                    showPhotoDetails = false
                                    viewModel.reprocessPhotoWithPaddle(versionId)
                                },
                                modifier = Modifier.weight(1f),
                                enabled = originalPhoto != null &&
                                    translatedPhoto != null &&
                                    viewModel.installedMobileOcr(versionId)
                            ) { Text(label, color = Mint, fontSize = 12.sp) }
                        }
                }
                if (viewModel.photoComparison.isNotEmpty()) {
                    viewModel.photoComparison.forEach { (version, result) ->
                        Text(
                            (if (version == "mobile_v5") "Paddle v5" else "Paddle v6") +
                                "：" + result,
                            color = Mint, fontSize = 12.sp
                        )
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
                snapshotLines.forEachIndexed { index, line ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF253844))
                            .clickable {
                                editOcrIndex = index
                                editOcrText = line.original
                                editTranslationText = line.translated.orEmpty()
                                showPhotoDetails = false
                            }
                            .padding(horizontal = 13.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text((index + 1).toString() + ". " + line.original,
                            color = Color.White, fontSize = 14.sp)
                        Text(line.provider + " · " + (line.translated ?: "尚未翻譯"),
                            color = Muted, fontSize = 12.sp)
                    }
                }
                Text("點選段落可修改 OCR 原文與譯文",
                    color = Mint, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 20.dp))
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
