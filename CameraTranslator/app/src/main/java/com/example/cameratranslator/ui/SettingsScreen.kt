package com.example.cameratranslator.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cameratranslator.core.MainViewModel
import com.example.cameratranslator.translation.ModelState
import com.example.cameratranslator.translation.ModelStatus

private val Ink = Color(0xFF162A36)
private val Muted = Color(0xFF58707D)
private val Fog = Color(0xFFF3F7F8)
private val Navy = Color(0xFF102A38)
private val Teal = Color(0xFF147F75)
private val PaleTeal = Color(0xFFE4F5F1)
private val BorderColor = Color(0xFFE2EAEA)
private val ErrorColor = Color(0xFFA43D3C)

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(21.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, BorderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun Heading(number: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(number, color = Teal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun StatusPill(label: String, success: Boolean, danger: Boolean = false) {
    val bg = when {
        danger -> Color(0xFFFDEDEA)
        success -> PaleTeal
        else -> Color(0xFFEFF3F4)
    }
    val fg = when {
        danger -> ErrorColor
        success -> Teal
        else -> Muted
    }
    Surface(shape = RoundedCornerShape(30.dp), color = bg) {
        Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp))
    }
}

@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    var newToken by remember { mutableStateOf("") }
    var revealToken by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    val models by viewModel.modelStatuses.collectAsState()
    val hasKey = viewModel.cloudTokenConfigured
    val state = viewModel.cloudConnectionState
    val isTesting = state == "testing"
    val isVerified = state == "verified"
    val isFailed = state == "failed"

    LaunchedEffect(Unit) { viewModel.refreshModelStatuses() }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清除 App 存取密鑰？") },
            text = { Text("確定清除後將無法使用 Azure，直到重新輸入密鑰。離線翻譯模型不受影響。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearCloudToken()
                    newToken = ""
                    feedback = "已清除儲存的密鑰"
                    showClearConfirm = false
                }) { Text("清除", color = ErrorColor) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    Column(Modifier.fillMaxSize().background(Fog).verticalScroll(rememberScrollState())) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(Brush.linearGradient(listOf(Navy, Color(0xFF235C5E))))
                .padding(horizontal = 20.dp, vertical = 23.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFBBF4EB))) {
                Text("‹  返回相機")
            }
            Text("翻譯設定", color = Color.White,
                fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("雲端連線、語言與離線模型",
                color = Color.White.copy(alpha = 0.80f), fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(if (hasKey) "✓ 密鑰已儲存" else "未設定密鑰",
                    success = hasKey, danger = !hasKey)
                StatusPill(if (isVerified) "✓ Azure 已驗證" else "雲端未驗證",
                    success = isVerified, danger = isFailed)
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {

            Panel {
                Heading("01  /  SECURITY", "App 密鑰與連線",
                    "Azure 只接收 OCR 文字；PP-OCRv5/v6 Mobile 在手機辨識照片，不上傳圖片。")
                val title = when {
                    !hasKey -> "尚未加入 App 密鑰"
                    isVerified -> "已儲存，且 Azure 連線成功"
                    isTesting -> "已儲存，正在測試連線"
                    isFailed -> "密鑰已儲存，但連線失敗"
                    else -> "密鑰已加密儲存"
                }
                val detail = when {
                    !hasKey -> "請貼上與 Cloudflare Secret 相同的 APP_ACCESS_TOKEN。"
                    isVerified -> "已實際取得 Azure 翻譯回應。"
                    isTesting -> "正在驗證，已儲存的密鑰不會顯示。"
                    isFailed -> "請檢查 Worker、Token 與 Azure 額度，密鑰本身仍安全儲存。"
                    else -> "已安全保存在 Android Keystore，不需要再次輸入。可按下方測試連線。"
                }
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (!hasKey || isFailed)
                            Color(0xFFFFF1EE) else PaleTeal)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(if (!hasKey || isFailed) Color(0xFFF9DCD6)
                                else Color(0xFFC5E9E1)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (isVerified) "✓" else if (hasKey) "●" else "!",
                            color = if (!hasKey || isFailed) ErrorColor else Teal,
                            fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, color = Ink, fontSize = 14.sp,
                            fontWeight = FontWeight.Bold)
                        Text(detail, color = Muted, fontSize = 12.sp,
                            lineHeight = 18.sp)
                    }
                }
                HorizontalDivider(color = BorderColor)
                Text(if (hasKey) "更換密鑰" else "首次加入密鑰",
                    color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(if (hasKey) "下面的空白輸入框是正常的：基於安全性，已存入的密鑰不會重新顯示。只有需要更換時才輸入。"
                    else "請貼上 APP_ACCESS_TOKEN，不是 Azure Key 1。至少需要 32 字元。",
                    color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                OutlinedTextField(
                    value = newToken,
                    onValueChange = { newToken = it; feedback = "" },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("APP_ACCESS_TOKEN") },
                    placeholder = { Text(if (hasKey) "已儲存，無需重填"
                        else "貼上從 Cloudflare 設定的密鑰") },
                    visualTransformation = if (revealToken)
                        VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { revealToken = !revealToken }) {
                            Text(if (revealToken) "隱藏" else "顯示")
                        }
                    },
                    supportingText = {
                        Text(if (newToken.isBlank()) "已儲存的密鑰不可查看，只能更換"
                            else "目前輸入 " + newToken.length + " 字元（至少 32 字元）")
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp)
                )
                Button(
                    onClick = {
                        feedback = if (viewModel.saveCloudToken(newToken)) {
                            newToken = ""
                            revealToken = false
                            "✓ 已加密儲存。現在可以測試 Azure 連線。"
                        } else "儲存失敗：密鑰需 32～256 字元且不能包含空白"
                    },
                    enabled = newToken.length in 32..256 && !isTesting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal),
                    shape = RoundedCornerShape(13.dp)
                ) { Text(if (hasKey) "儲存新密鑰（取代舊的）" else "儲存密鑰") }
                if (feedback.isNotBlank()) Text(feedback,
                    color = if (feedback.startsWith("儲存失敗")) ErrorColor else Teal,
                    fontSize = 12.sp)
                OutlinedButton(
                    onClick = viewModel::testCloudConnection,
                    enabled = hasKey && !isTesting,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isTesting) "正在測試…" else "測試 Azure 雲端連線")
                }
                if (hasKey) {
                    Text("測試結果：" + viewModel.cloudTestStatus,
                        color = if (isFailed) ErrorColor else Muted,
                        fontSize = 12.sp, lineHeight = 18.sp)
                    TextButton(onClick = { showClearConfirm = true }) {
                        Text("清除已儲存密鑰", color = ErrorColor, fontSize = 12.sp)
                    }
                }
                Text("Worker：camera-translator-api.jimmy0933.workers.dev",
                    color = Muted, fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            Panel {
                Heading("02  /  TRANSLATION", "翻譯模式",
                    "選擇照片翻譯時優先使用的引擎。離線模式不傳送文字到雲端。")
                listOf(
                    Triple("auto", "智慧自動", "有網路優先 Azure，失敗時使用離線模型"),
                    Triple("cloud", "雲端優先", "先嘗試 Azure，失敗才用 ML Kit"),
                    Triple("offline", "僅離線", "文字不離開手機，適合私密內容")
                ).forEach { (code, name, detail) ->
                    val selected = viewModel.translationMode == code
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) PaleTeal else Color(0xFFF7FAFA))
                            .clickable { viewModel.updateTranslationMode(code) }
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected,
                            onClick = { viewModel.updateTranslationMode(code) },
                            colors = RadioButtonDefaults.colors(selectedColor = Teal))
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = Ink, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text(detail, color = Muted, fontSize = 11.sp,
                                lineHeight = 17.sp)
                        }
                    }
                }
            }

            Panel {
                Heading("03  /  MOBILE OCR", "拍照辨識引擎",
                    "真正的手機端 ONNX 推論。拍照後的座標會直接用於原圖替字；模型只需下載一次。")
                listOf(
                    Triple("mobile_v6", "PP-OCRv6 Small（建議）",
                        "支援中／英／日 OCR，使用 ONNX Runtime 在手機離線辨識"),
                    Triple("mobile_v5", "PP-OCRv5 Mobile",
                        "舊版輕量辨識模型，可和 v6 比較辨識結果"),
                    Triple("mlkit", "ML Kit 本機 OCR",
                        "無須額外下載 PP 模型；泰文維持 Tesseract"),
                    Triple("cloud_v5", "PP-OCRv5 雲端服務",
                        "需自行部署 OCR 上游服務，可能產生費用")
                ).forEach { (key, title, detail) ->
                    val selected = viewModel.photoOcrMode == key
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(if (selected) PaleTeal else Color(0xFFF7FAFA))
                            .clickable { viewModel.selectPhotoOcrMode(key) }
                            .padding(vertical = 6.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { viewModel.selectPhotoOcrMode(key) },
                            colors = RadioButtonDefaults.colors(selectedColor = Teal)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(title, fontSize = 14.sp, color = Ink,
                                fontWeight = FontWeight.SemiBold)
                            Text(detail, fontSize = 11.sp, color = Muted, lineHeight = 16.sp)
                        }
                    }
                }
                listOf(
                    "V5" to "PP-OCRv5 Mobile",
                    "V6" to "PP-OCRv6 Small"
                ).forEach { (id, title) ->
                    HorizontalDivider(color = BorderColor)
                    val installed = viewModel.installedMobileOcr(id)
                    val status = viewModel.mobileModelStatuses[id] ?: "尚未下載"
                    Row(Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(title, color = Ink, fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text(status, color = if (installed) Teal else Muted,
                                fontSize = 11.sp, lineHeight = 17.sp)
                        }
                        OutlinedButton(
                            onClick = { viewModel.downloadMobileOcr(id, force = installed) },
                            enabled = !status.startsWith("下載中"),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            shape = RoundedCornerShape(11.dp)
                        ) {
                            Text(if (status.startsWith("下載中")) "下載中" else if (installed)
                                "重新下載" else "下載")
                        }
                    }
                }
                Text("版本切換後，新拍的照片才會使用所選模型。若尚未下載或推論失敗，" +
                    "會明確顯示 ML Kit 備援。v6 Small 支援日文，v6 Tiny 不支援日文。" +
                    "泰文維持專用 Tesseract OCR。首次下載需網路，ONNX 模型從官方 PaddlePaddle 來源取得。",
                    color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
                StatusPill(
                    "上次拍照辨識：" + viewModel.actualPhotoOcrEngine,
                    success = viewModel.actualPhotoOcrEngine.contains("手機離線")
                )
                Text("Paddle 診斷：" + viewModel.photoOcrDiagnostics,
                    color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
            }

            Panel {
                Heading("04  /  THAI OCR", "泰文拍照辨識",
                    "英文、中文和日文辨識已內建；泰文需要額外下載約 1 MB 模型。")
                val thaiStatus = viewModel.thaiOcrModelStatus
                val isReady = thaiStatus.startsWith("已下載")
                val downloading = thaiStatus.startsWith("下載中")
                StatusPill(thaiStatus, success = isReady,
                    danger = thaiStatus.startsWith("下載失敗"))
                OutlinedButton(
                    onClick = viewModel::downloadThaiOcrModel,
                    enabled = !downloading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(if (isReady) "確認泰文 OCR 模型" else if (downloading)
                    "正在下載…" else "下載／重試泰文 OCR 模型") }
            }

            Panel {
                Heading("05  /  DOWNLOADS", "離線語言模型",
                    "可個別下載。已安裝狀態會保留，下載中可稍後重新檢查。")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("僅透過 Wi-Fi 下載", color = Ink,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("避免消耗行動數據", color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = viewModel.wifiOnly,
                        onCheckedChange = viewModel::updateWifiOnly,
                        colors = SwitchDefaults.colors(checkedTrackColor = Teal))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Button(
                        onClick = viewModel::downloadAllModels,
                        enabled = models.values.none { it.state == ModelState.DOWNLOADING },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("下載全部") }
                    OutlinedButton(
                        onClick = viewModel::refreshModelStatuses,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("重新檢查") }
                }
                listOf(
                    "zh" to "繁體中文",
                    "en" to "英文",
                    "ja" to "日文",
                    "th" to "泰文"
                ).forEachIndexed { index, (code, title) ->
                    if (index > 0) HorizontalDivider(color = BorderColor)
                    val model = models[code] ?: ModelStatus()
                    Row(Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(title, color = Ink, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text(when (model.state) {
                                ModelState.READY -> "✓ 已下載"
                                ModelState.DOWNLOADING -> "下載中"
                                ModelState.FAILED -> "下載失敗，可重試"
                                ModelState.NOT_DOWNLOADED -> "尚未下載"
                            }, color = if (model.state == ModelState.FAILED)
                                ErrorColor else Muted, fontSize = 12.sp)
                            if (model.detail.isNotBlank())
                                Text(model.detail, color = Muted, fontSize = 11.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (model.state == ModelState.DOWNLOADING)
                            CircularProgressIndicator(Modifier.size(20.dp),
                                strokeWidth = 2.dp, color = Teal)
                        else if (model.state != ModelState.READY)
                            OutlinedButton(
                                onClick = { viewModel.downloadModel(code) },
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text(if (model.state == ModelState.FAILED) "重試" else "下載") }
                        else StatusPill("已就緒", success = true)
                    }
                }
                Text("ML Kit 無法提供可靠百分比，因此只顯示實際下載狀態。",
                    color = Muted, fontSize = 11.sp)
            }

            Panel {
                Heading("06  /  PRIVACY", "照片與隱私",
                    "按下快門或選取相簿照片後才會開始 OCR 辨識。")
                Text("• 拍攝的照片保留在手機。\n" +
                    "• 雲端翻譯只傳送 OCR 辨識文字。\n" +
                    "• Azure F0 用量與額度請在 Azure 控制台確認。",
                    color = Muted, fontSize = 13.sp, lineHeight = 23.sp)
            }

            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Navy),
                shape = RoundedCornerShape(15.dp)
            ) { Text("返回相機", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(15.dp))
        }
    }
}
