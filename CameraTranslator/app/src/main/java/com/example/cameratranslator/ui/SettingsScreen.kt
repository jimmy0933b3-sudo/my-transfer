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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cameratranslator.core.MainViewModel
import com.example.cameratranslator.translation.MLKitTranslationProvider
import com.example.cameratranslator.translation.ModelState
import com.example.cameratranslator.translation.ModelStatus

@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    var opacity by remember { mutableFloatStateOf(viewModel.overlayOpacity) }
    var accessToken by remember { mutableStateOf("") }
    var tokenFeedback by remember { mutableStateOf("") }
    val statuses by viewModel.modelStatuses.collectAsState()
    val languages = listOf(
        "zh" to "繁體中文",
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
        Text("高解析度拍照翻譯支援英文、日文、中文及泰文；來源 Auto 也可搭配拍照辨識。泰文即時相機 OCR 尚未支援。", style = MaterialTheme.typography.bodySmall)
        Text("相機上方可手動選來源與目標語系。暫停後點文字框即可複製；原文按鈕可顯示 OCR 結果。")

        Text("翻譯框不透明度：" + (opacity * 100).toInt() + "%")
        Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0.35f..1f)

        HorizontalDivider()
        Text("雲端翻譯（Azure F0）", style = MaterialTheme.typography.titleLarge)
        Text(
            "文字會經 Cloudflare Worker 傳送至 Azure，圖片不會上傳。Azure 金鑰只保存在 Worker，App 不包含 Azure 金鑰。",
            style = MaterialTheme.typography.bodySmall
        )
        listOf(
            "auto" to "自動：有網路就優先 Azure（含 4G/5G），無網路用離線",
            "cloud" to "雲端優先：有網路就嘗試雲端，失敗切離線",
            "offline" to "僅離線：不傳送文字到雲端"
        ).forEach { (code, label) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = viewModel.translationMode == code,
                    onClick = { viewModel.updateTranslationMode(code) }
                )
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            if (viewModel.cloudTokenConfigured) "App 存取密鑰：已加密儲存"
            else "App 存取密鑰：尚未設定，將使用離線模式",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = accessToken,
            onValueChange = { accessToken = it },
            label = { Text("APP_ACCESS_TOKEN（至少 32 字）") },
            supportingText = { Text("與 Cloudflare Worker Secret 使用相同內容，不是 Azure Key。") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = accessToken.length >= 32,
                onClick = {
                    tokenFeedback = if (viewModel.saveCloudToken(accessToken)) {
                        accessToken = ""
                        "密鑰已加密儲存"
                    } else "儲存失敗，檢查長度、空白字元或手機安全設定"
                }
            ) { Text("儲存密鑰") }
            OutlinedButton(onClick = {
                viewModel.clearCloudToken()
                accessToken = ""
                tokenFeedback = "已清除"
            }) { Text("清除") }
        }
        if (tokenFeedback.isNotBlank()) {
            Text(tokenFeedback, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = viewModel::testCloudConnection,
            enabled = viewModel.cloudTokenConfigured
        ) { Text("測試 Azure 雲端連線") }
        Text(
            viewModel.cloudTestStatus,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Worker 網址：https://camera-translator-api.jimmy0933.workers.dev\n" +
                "如未在 Cloudflare 建立 APP_ACCESS_TOKEN Secret，Worker 會拒絕任何翻譯呼叫。",
            style = MaterialTheme.typography.bodySmall
        )
        HorizontalDivider()
        Text("泰文 OCR 模型（拍照翻譯）", style = MaterialTheme.typography.titleLarge)
        Text(
            "ML Kit 不支援泰文字形，泰文使用 Tesseract OCR。首次下載免費模型，之後不需網路也可辨識；雲端翻譯仍只傳 OCR 文字。",
            style = MaterialTheme.typography.bodySmall
        )
        Text(viewModel.thaiOcrModelStatus, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = viewModel::downloadThaiOcrModel) {
            Text("下載／重試泰文 OCR 模型")
        }
        HorizontalDivider()
        Text("離線語言模型", style = MaterialTheme.typography.titleLarge)
        Text(
            "請下載所選來源與目標的語言模型；兩者相同時只做 OCR，不需要下載翻譯模型。",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("只使用 Wi-Fi 下載")
                Text("預設開啟，避免使用行動數據", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = viewModel.wifiOnly,
                onCheckedChange = viewModel::updateWifiOnly
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
        HorizontalDivider()
        Text("翻譯品質與用量", style = MaterialTheme.typography.titleLarge)
        Text(
            "已支援 Azure Translator F0 雲端翻譯。翻譯失敗時會改用已下載的 ML Kit 模型。" +
                "OCR 同段落重複辨識會使用本機快取，仍請在 Azure 查看用量和額度；" +
                "Cloudflare 單一 Worker 執行個體的限速不等於全域保證上限。",
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
