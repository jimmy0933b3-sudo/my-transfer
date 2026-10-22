package com.example.cameratranslator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cameratranslator.core.MainViewModel
import com.example.cameratranslator.translation.MLKitTranslationProvider
import com.example.cameratranslator.translation.ModelState
import com.example.cameratranslator.translation.ModelStatus

@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    var opacity by remember { mutableFloatStateOf(viewModel.overlayOpacity) }
    val statuses by viewModel.modelStatuses.collectAsState()
    val languages = listOf(
        "zh" to "繁體中文（翻譯目標）",
        "en" to "英文",
        "ja" to "日文",
        "th" to "泰文"
    )

    LaunchedEffect(Unit) { viewModel.refreshModelStatuses() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("← 返回相機") }
        Text("翻譯設定", style = MaterialTheme.typography.headlineMedium)

        Text("翻譯框不透明度：" + (opacity * 100).toInt() + "%")
        Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0.35f..1f)

        HorizontalDivider()
        Text("離線語言模型", style = MaterialTheme.typography.titleLarge)
        Text(
            "先下載「繁體中文」及需要翻譯的來源語言。下載完成後即可離線翻譯。",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("只使用 Wi-Fi 下載")
                Text("預設開啟，避免使用行動數據", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = viewModel.wifiOnly,
                onCheckedChange = viewModel::setWifiOnly
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::downloadAllModels,
                modifier = Modifier.weight(1f),
                enabled = statuses.values.none { it.state == ModelState.DOWNLOADING }
            ) { Text("下載全部模型") }

            OutlinedButton(
                onClick = viewModel::refreshModelStatuses,
                modifier = Modifier.weight(1f)
            ) { Text("重新檢查") }
        }

        languages.forEach { (code, label) ->
            val status = statuses[code] ?: ModelStatus()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (status.state == ModelState.DOWNLOADING) {
                            CircularProgressIndicator(strokeWidth = 2.dp,
                                modifier = Modifier.height(18.dp).padding(end = 2.dp))
                        }
                        Text(
                            when (status.state) {
                                ModelState.READY -> "已下載"
                                ModelState.DOWNLOADING -> "下載中"
                                ModelState.FAILED -> "下載中斷／失敗"
                                ModelState.NOT_DOWNLOADED -> "尚未下載"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(status.detail, style = MaterialTheme.typography.bodySmall)
                    if (status.state != ModelState.READY) {
                        Button(
                            onClick = { viewModel.downloadModel(code) },
                            enabled = status.state != ModelState.DOWNLOADING
                        ) {
                            Text(if (status.state == ModelState.FAILED) "繼續下載／重試" else "下載")
                        }
                    }
                }
            }
        }

        Text(
            "下載由 Google ML Kit 管理，可重新嘗試尚未完成的模型；" +
                "Android 版不提供可靠的下載百分比，因此使用狀態顯示，不顯示虛假的進度。" ,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "注意：泰文翻譯模型可下載，但泰文相機文字辨識仍需要另外完成 OCR 支援。",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.saveOverlayOpacity(opacity); onBack() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("確定並返回相機") }
        Spacer(Modifier.height(20.dp))
    }
}
