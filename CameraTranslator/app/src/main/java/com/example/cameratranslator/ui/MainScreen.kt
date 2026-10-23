package com.example.cameratranslator.ui

import android.widget.Toast
import androidx.compose.foundation.background
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
    LanguageChoice("th", "泰文（OCR 尚未支援）", false)
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
        }
        if (status != null && !paused) {
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
        Row(
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
            }) { Text(if (showOriginal) "翻譯" else "原文") }
        }
    }
}
