package com.example.cameratranslator.core

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.Bitmap
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import com.example.cameratranslator.camera.SnapshotBitmapDecoder
import java.io.File
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.camera.view.LifecycleCameraController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cameratranslator.core.models.TextElement
import com.example.cameratranslator.history.HistoryDao
import com.example.cameratranslator.history.TranslationHistory
import com.example.cameratranslator.ocr.MLKitOcrProvider
import com.example.cameratranslator.ocr.ThaiPhotoOcrProvider
import com.example.cameratranslator.translation.MLKitTranslationProvider
import com.example.cameratranslator.translation.CloudCredentialStore
import com.example.cameratranslator.translation.CloudTranslationProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val historyDao: HistoryDao
) : AndroidViewModel(application) {
    private val photoOcr = MLKitOcrProvider(application)
    private val thaiOcrModels = ThaiPhotoOcrProvider(application)
    var thaiOcrModelStatus by mutableStateOf(
        if (thaiOcrModels.isModelInstalled()) "已下載，可離線辨識泰文"
        else "尚未下載（約 1 MB）"
    )
        private set

    fun downloadThaiOcrModel() {
        if (thaiOcrModels.isModelInstalled()) {
            thaiOcrModelStatus = "已下載，可離線辨識泰文"
            return
        }
        thaiOcrModelStatus = "下載中，請保持網路連線…"
        viewModelScope.launch {
            try {
                thaiOcrModels.downloadModel()
                thaiOcrModelStatus = "已下載，可離線辨識泰文"
            } catch (e: Exception) {
                thaiOcrModelStatus = "下載失敗，請點重試：" + (e.message ?: "網路錯誤")
            }
        }
    }

    private val translator = MLKitTranslationProvider()
    private val cloudTranslator = CloudTranslationProvider()
    private val cloudCredentials = CloudCredentialStore(application)
    val modelStatuses = translator.modelStatuses
    var wifiOnly by androidx.compose.runtime.mutableStateOf(
        application.getSharedPreferences("translator_settings", 0)
            .getBoolean("models_wifi_only", true)
    )
        private set

    init {
        refreshModelStatuses()
    }

    fun updateWifiOnly(value: Boolean) {
        wifiOnly = value
        preferences.edit().putBoolean("models_wifi_only", value).apply()
    }

    fun refreshModelStatuses() {
        viewModelScope.launch(Dispatchers.IO) {
            translator.refreshModelStatuses()
        }
    }

    fun downloadModel(language: String) {
        viewModelScope.launch(Dispatchers.IO) {
            translator.downloadLanguage(language, wifiOnly)
        }
    }

    fun downloadAllModels() {
        viewModelScope.launch(Dispatchers.IO) {
            for (language in MLKitTranslationProvider.supportedLanguages) {
                translator.downloadLanguage(language, wifiOnly)
            }
        }
    }


    private val _snapshotStatus = MutableStateFlow<String?>(null)
    val snapshotStatus: StateFlow<String?> = _snapshotStatus.asStateFlow()
    private val _snapshotLines = MutableStateFlow<List<SnapshotTranslationLine>>(emptyList())
    val snapshotLines: StateFlow<List<SnapshotTranslationLine>> = _snapshotLines.asStateFlow()

    fun closeSnapshot() {
        _snapshotStatus.value = null
        _snapshotLines.value = emptyList()
        isPaused = false
        resetRecognitions()
    }

    fun captureSnapshot(context: Context) {
        if (_snapshotStatus.value != null) return
        val camera = cameraController ?: run {
            _statusMessage.value = "相機尚未準備好，請重新開啟"
            return
        }
        isPaused = true
        _snapshotLines.value = emptyList()
        _snapshotStatus.value = "正在拍攝高解析度照片…"
        val file = try {
            File.createTempFile("camera-ocr-", ".jpg", context.cacheDir)
        } catch (_: Exception) {
            _snapshotStatus.value = "無法建立暫存照片，請檢查儲存空間"
            return
        }

        try {
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            camera.takePicture(options, ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                _snapshotStatus.value = if (sourceLanguage == "th")
                                    "正在準備泰文 OCR 模型並辨識照片（首次使用需網路）…"
                                else "正在辨識完整文字區塊…"

                                val bitmap = SnapshotBitmapDecoder.decode(file)
                                    ?: error("無法讀取照片")
                                val requestedSource = sourceLanguage
                                val requestedTarget = targetLanguage
                                val raw = try {
                                    photoOcr.recognizeTextFromBitmap(bitmap, requestedSource)
                                } finally { bitmap.recycle() }
                                if (thaiOcrModels.isModelInstalled()) {
                                    thaiOcrModelStatus = "已下載，可離線辨識泰文"
                                }
                                val selected = raw.filter {
                                    !it.originalText.isBlank() && it.originalText.length > 1
                                }.distinctBy { it.originalText.trim() }.take(18)
                                if (selected.isEmpty()) {
                                    _snapshotStatus.value = "沒有辨識到清楚文字，請靠近、避開反光再拍攝"
                                    return@launch
                                }
                                _snapshotLines.value = selected.map {
                                    SnapshotTranslationLine(it.originalText.trim())
                                }
                                _snapshotStatus.value = "辨識到 " + selected.size + " 段，逐段翻譯中…"
                                val result = _snapshotLines.value.toMutableList()
                                var translatedCount = 0
                                for ((i, block) in selected.withIndex()) {
                                    val original = block.originalText.trim()
                                    // Product trademarks should not turn into strange
                                    // Chinese transliterations on JA/ZH/TH packaging.
                                    // Restrict this to short Latin-only logo-style names.
                                    val brand = requestedSource != "en" &&
                                        block.sourceLanguage != "th" &&
                                        original.length <= 50 &&
                                        original.any { it in 'A'..'Z' } &&
                                        original.none { it in '\u3040'..'\u30ff' ||
                                            it in '\u3400'..'\u9fff' ||
                                            it in '\u0E00'..'\u0E7F' } &&
                                        original.all { it.isLetterOrDigit() ||
                                            it.isWhitespace() || it in "-®©.&" } &&
                                        original.split(Regex("\\s+")).size <= 5
                                    val translated: String?
                                    val provider: String
                                    if (requestedSource == requestedTarget ||
                                        brand || block.sourceLanguage == requestedTarget
                                    ) {
                                        translated = original
                                        provider = if (brand) "品牌名稱保留原文" else "相同語言原文"
                                    } else {
                                        val cloud = if (shouldTryCloud())
                                            cloudTranslator.translate(
                                                original.take(1500),
                                                block.sourceLanguage, requestedTarget,
                                                cloudCredentials.load().orEmpty()
                                            ) else null
                                        if (!cloud.isNullOrBlank()) {
                                            translated = cloud
                                            provider = "Azure 雲端"
                                        } else {
                                            translated = if (translator.isReadyFor(
                                                block.sourceLanguage, requestedTarget
                                            )) translator.translate(
                                                original.take(1500),
                                                block.sourceLanguage, requestedTarget
                                            ) else null
                                            provider = if (translated != null) "ML Kit 離線"
                                                else "未翻譯，請檢查 Azure／離線模型"
                                        }
                                    }
                                    if (!translated.isNullOrBlank()) translatedCount++
                                    result[i] = result[i].copy(
                                        translated = translated, provider = provider
                                    )
                                    _snapshotLines.value = result.toList()
                                }
                                _snapshotStatus.value = "共 " + selected.size + " 段，成功 " +
                                    translatedCount + " 段。請比對原文及譯文。"
                            } catch (e: Exception) {
                                Log.w("CameraTranslator", "Still photo translation failed", e)
                                _snapshotStatus.value = if (sourceLanguage == "th")
                                    "泰文 OCR 發生問題：" + (e.message ?: "請重拍或檢查模型")
                                else "拍照辨識失敗：請重新拍攝並確認對焦"
                            } finally {
                                file.delete()
                            }
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        file.delete()
                        Log.w("CameraTranslator", "Camera photo capture error", exception)
                        _snapshotStatus.value = "拍照失敗：請稍後重新拍攝"
                    }
                }
            )
        } catch (e: Exception) {
            file.delete()
            Log.w("CameraTranslator", "Unable to start photo capture", e)
            _snapshotStatus.value = "無法拍照，請重新開啟相機"
        }
    }

    private val _textElements = MutableStateFlow<List<TextElement>>(emptyList())
    val textElements: StateFlow<List<TextElement>> = _textElements.asStateFlow()
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()
    // Actual last successful translation provider, not the requested mode.
    private val _translationEngine = MutableStateFlow<String?>(null)
    val translationEngine: StateFlow<String?> = _translationEngine.asStateFlow()

    private data class Tracked(
        val element: TextElement,
        val lastSeenMs: Long,
        val candidateText: String? = null,
        val candidateCount: Int = 0,
        val seenCount: Int = 1
    )
    private val tracked = linkedMapOf<String, Tracked>()
    private var nextTrackId = 0L
    private var lastSampleMs = 0L
    private val cache = mutableMapOf<String, String>()
    private val cacheEngine = mutableMapOf<String, String>()
    private val pending = mutableSetOf<String>()
    private val retryAfter = mutableMapOf<String, Long>()
    // Cloud failures open a 60s circuit breaker. Never alternate the label
    // between cloud and offline for every recognized word on a bad network.
    @Volatile private var cloudRetryAfterMs = 0L
    private val preferences = application.getSharedPreferences("translator_settings", 0)

    var translationMode by mutableStateOf(preferences.getString("translation_mode", "auto") ?: "auto")
        private set
    var cloudTokenConfigured by mutableStateOf(cloudCredentials.load() != null)
        private set
    var cloudTestStatus by mutableStateOf("尚未測試")
        private set

    /** Explicit diagnostic call. Does not fall back to ML Kit or fake success. */
    fun testCloudConnection() {
        if (!cloudTokenConfigured) {
            cloudTestStatus = "請先儲存 APP_ACCESS_TOKEN"
            return
        }
        cloudTestStatus = "連線測試中…"
        viewModelScope.launch {
            val result = cloudTranslator.translate(
                "Hello world", "en", "zh", cloudCredentials.load().orEmpty()
            )
            cloudTestStatus = if (!result.isNullOrBlank() && result != "Hello world")
                "Azure 雲端測試成功：$result"
            else "雲端測試失敗：請檢查 Worker /health、APP_ACCESS_TOKEN 與 Azure 免費額度"
        }
    }

    fun updateTranslationMode(mode: String) {
        if (mode !in listOf("auto", "cloud", "offline") || mode == translationMode) return
        translationMode = mode
        preferences.edit().putString("translation_mode", mode).apply()
        cache.clear()
        cacheEngine.clear()
        resetRecognitions()
    }

    fun saveCloudToken(token: String): Boolean {
        val saved = cloudCredentials.save(token)
        if (saved) {
            cloudTokenConfigured = true
            cloudTestStatus = "密鑰已保存，請按「測試雲端」驗證"
            cache.clear()
            cacheEngine.clear()
            resetRecognitions()
        }
        return saved
    }

    fun clearCloudToken() {
        cloudCredentials.clear()
        cloudTokenConfigured = false
        cloudTestStatus = "已清除 App 密鑰"
        cache.clear()
        cacheEngine.clear()
        resetRecognitions()
    }

    private fun shouldTryCloud(): Boolean {
        if (translationMode == "offline" || !cloudTokenConfigured ||
            SystemClock.elapsedRealtime() < cloudRetryAfterMs) return false
        if (translationMode == "cloud") return true
        val manager = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false)
            ?: return false
        // Auto should work on both Wi-Fi and mobile data. Previously it
        // silently forced offline on 4G, leaving users with no translation if
        // the offline model had not been downloaded.
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }


    companion object {
        // Updating the UI for every OCR frame magnifies harmless recognition noise.
        private const val SAMPLE_INTERVAL_MS = 420L
        private const val LOST_TRACK_GRACE_MS = 1850L
        private const val TEXT_CONFIRM_FRAMES = 3
        private const val MAX_LIVE_SEGMENTS = 4
    }

    var sourceLanguage by mutableStateOf(preferences.getString("source_language", "auto") ?: "auto")
        private set
    var targetLanguage by mutableStateOf(preferences.getString("target_language", "zh") ?: "zh")
        private set
    private var languageRevision = 0

    fun updateSourceLanguage(code: String) {
        if (code !in listOf("auto", "en", "ja", "zh") || sourceLanguage == code) return
        sourceLanguage = code
        preferences.edit().putString("source_language", code).apply()
        resetRecognitions()
    }

    fun updateTargetLanguage(code: String) {
        if (code !in listOf("zh", "en", "ja", "th") || targetLanguage == code) return
        targetLanguage = code
        preferences.edit().putString("target_language", code).apply()
        resetRecognitions()
    }

    private fun resetRecognitions() {
        languageRevision++
        tracked.clear()
        _statusMessage.value = null
        _translationEngine.value = null
        cloudRetryAfterMs = 0L
        publish()
        lastSampleMs = 0L
    }

    var overlayOpacity by mutableFloatStateOf(preferences.getFloat("overlay_opacity", 0.88f))
        private set
    fun saveOverlayOpacity(value: Float) {
        overlayOpacity = value.coerceIn(0.35f, 1f)
        preferences.edit().putFloat("overlay_opacity", overlayOpacity).apply()
    }

    @Volatile var isPaused = false
    var showOriginal = false
    var cameraController: LifecycleCameraController? = null
    var isFlashlightOn = false
    fun toggleFlashlight() {
        isFlashlightOn = !isFlashlightOn
        cameraController?.enableTorch(isFlashlightOn)
    }

    fun showCameraError() {
        _statusMessage.value = "相機無法啟動，請檢查權限"
    }

    private fun normalized(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun key(element: TextElement): String =
        element.sourceLanguage + ":" + targetLanguage + ":" + normalized(element.originalText)

    /** CameraX invokes this on the main executor. No translation or download blocks OCR. */
    fun onCameraTextElements(elements: List<TextElement>) {
        if (isPaused) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return
        lastSampleMs = now
        publishDetections(elements, now)
    }

    private fun matchingTrack(candidate: TextElement, matched: Set<String>): String? {
        val box = candidate.boundingBox ?: return null
        val matchText = normalized(candidate.originalText)
        return tracked.entries.mapNotNull { (id, old) ->
            if (id in matched || old.element.sourceLanguage != candidate.sourceLanguage) {
                return@mapNotNull null
            }
            val previous = old.element.boundingBox ?: return@mapNotNull null
            val width = max(0, min(previous.right, box.right) - max(previous.left, box.left))
            val height = max(0, min(previous.bottom, box.bottom) - max(previous.top, box.top))
            val smallArea = min(previous.width().toLong() * previous.height(),
                box.width().toLong() * box.height()).coerceAtLeast(1L)
            val overlap = (width.toLong() * height).toFloat() / smallArea
            val distance = hypot((previous.centerX() - box.centerX()).toDouble(),
                (previous.centerY() - box.centerY()).toDouble()).toFloat()
            val scale = max(max(previous.width(), box.width()),
                max(previous.height(), box.height())).coerceAtLeast(1)
            val sameText = normalized(old.element.originalText) == matchText
            if (overlap < 0.58f && !(sameText && distance < scale * 0.38f)) {
                return@mapNotNull null
            }
            id to (overlap + (if (sameText) 1f else 0f) - distance / scale * 0.16f)
        }.maxByOrNull { it.second }?.first
    }

    private fun smoothBounds(previous: Rect?, fresh: Rect?): Rect? {
        if (fresh == null) return previous?.let(::Rect)
        if (previous == null) return Rect(fresh)
        fun smooth(old: Int, next: Int): Int {
            val delta = next - old
            return when {
                abs(delta) <= 7 -> old
                abs(delta) > 90 -> next
                else -> old + (delta * 0.38f).roundToInt()
            }
        }
        return Rect(
            smooth(previous.left, fresh.left),
            smooth(previous.top, fresh.top),
            smooth(previous.right, fresh.right),
            smooth(previous.bottom, fresh.bottom)
        )
    }

    private fun publishDetections(detected: List<TextElement>, now: Long = SystemClock.elapsedRealtime()) {
        val matched = mutableSetOf<String>()
        for (incoming in detected) {
            if (incoming.boundingBox == null) continue
            val id = matchingTrack(incoming, matched) ?: ("segment-" + (++nextTrackId))
            matched.add(id)
            val old = tracked[id]
            if (old == null) {
                tracked[id] = Tracked(
                    incoming.copy(id = id, translatedText = if (incoming.sourceLanguage == targetLanguage)
                        incoming.originalText else cache[key(incoming)]), now
                )
                continue
            }

            val unchanged = normalized(old.element.originalText) == normalized(incoming.originalText)
            val candidateCount = if (old.candidateText != null &&
                normalized(old.candidateText) == normalized(incoming.originalText)
            ) old.candidateCount + 1 else 1
            val confirmed = !unchanged && candidateCount >= TEXT_CONFIRM_FRAMES
            val accepted = if (confirmed) {
                // Keep the last visible translation while the newly confirmed
                // paragraph is translated instead of briefly clearing the card.
                incoming.copy(
                    id = id,
                    translatedText = if (incoming.sourceLanguage == targetLanguage)
                        incoming.originalText else cache[key(incoming)],
                    translationEngine = cacheEngine[key(incoming)]
                )
            } else {
                old.element.copy(
                    boundingBox = incoming.boundingBox,
                    isViewCoordinates = incoming.isViewCoordinates,
                    imageWidth = incoming.imageWidth,
                    imageHeight = incoming.imageHeight
                )
            }
            tracked[id] = Tracked(
                element = accepted.copy(
                    boundingBox = smoothBounds(old.element.boundingBox, incoming.boundingBox)
                ),
                lastSeenMs = now,
                candidateText = if (unchanged || confirmed) null else incoming.originalText,
                candidateCount = if (unchanged || confirmed) 0 else candidateCount,
                seenCount = if (unchanged) old.seenCount + 1
                    else if (confirmed) 1 else old.seenCount
            )
        }

        // A single missed frame must not clear the screen. Expire old boxes soon
        // enough that pointing at another object does not leave ghost text.
        val iterator = tracked.iterator()
        while (iterator.hasNext()) {
            val (id, old) = iterator.next()
            if (id !in matched && now - old.lastSeenMs > LOST_TRACK_GRACE_MS) iterator.remove()
        }

        for ((_, old) in tracked) {
            val element = old.element
            // A single noisy OCR frame is not enough to spend cloud quota
            // or render a hallucinated translation.
            if (old.seenCount < 3) continue
            val translationKey = key(element)
            if (element.sourceLanguage == targetLanguage) {
                if (element.translatedText != element.originalText) {
                    val track = tracked.entries.firstOrNull { it.value === old }
                    if (track != null) tracked[track.key] =
                        old.copy(element = element.copy(translatedText = element.originalText))
                }
                continue
            }
            val cached = cache[translationKey]
            if (!cached.isNullOrBlank()) {
                if (_translationEngine.value == null) {
                    _translationEngine.value = cacheEngine[translationKey]
                }
                continue
            }
            val cloudPreferred = shouldTryCloud()
            val offlineReady = translator.isReadyFor(element.sourceLanguage, targetLanguage)
            if (!cloudPreferred && !offlineReady) {
                _statusMessage.value = if (cloudTokenConfigured && translationMode != "offline")
                    "雲端暫時不可用且離線模型未就緒，請到設定測試 Azure 或下載模型"
                else "離線模型尚未就緒，請到設定下載語言模型"
                continue
            }
            // Serialize translations. With multiple parallel requests, slow
            // replies made the provider badge jump Azure → offline → Azure.
            if (translationKey in pending || pending.isNotEmpty() ||
                now < (retryAfter[translationKey] ?: 0L)) continue
            pending.add(translationKey)
            _statusMessage.value = null
            val requestedTarget = targetLanguage
            val requestedRevision = languageRevision
            viewModelScope.launch(Dispatchers.IO) {
                var actualEngine: String? = null
                var cloudFailed = false
                val translated = try {
                    val cloudResult = if (cloudPreferred) {
                        cloudTranslator.translate(
                            element.originalText, element.sourceLanguage, requestedTarget,
                            cloudCredentials.load().orEmpty()
                        )
                    } else null
                    if (!cloudResult.isNullOrBlank()) {
                        actualEngine = "Azure 雲端"
                        cloudResult
                    } else if (offlineReady) {
                        if (cloudPreferred) cloudFailed = true
                        val offlineResult = translator.translate(
                            element.originalText, element.sourceLanguage, requestedTarget
                        )
                        if (!offlineResult.isNullOrBlank()) actualEngine = "ML Kit 離線"
                        offlineResult
                    } else {
                        if (cloudPreferred) cloudFailed = true
                        null
                    }
                } catch (e: Exception) {
                    Log.w("CameraTranslator", "Translation failed", e)
                    null
                }
                withContext(Dispatchers.Main) {
                    pending.remove(translationKey)
                    if (requestedRevision != languageRevision) return@withContext
                    if (cloudFailed) {
                        cloudRetryAfterMs = SystemClock.elapsedRealtime() + 60_000L
                    } else if (actualEngine == "Azure 雲端") {
                        cloudRetryAfterMs = 0L
                    }
                    // Brand names and proper nouns often remain unchanged in
                    // the translation. That is a valid response, not a failure.
                    if (!translated.isNullOrBlank()) {
                        cache[translationKey] = translated
                        if (actualEngine != null) {
                            cacheEngine[translationKey] = actualEngine!!
                            _translationEngine.value = actualEngine
                        }
                        // A delayed result may update only the paragraph that asked
                        // for this exact text, never an unrelated box at that location.
                        tracked.forEach { (id, current) ->
                            if (key(current.element) == translationKey) {
                                tracked[id] = current.copy(
                                    element = current.element.copy(
                                        translatedText = translated,
                                        translationEngine = actualEngine
                                    )
                                )
                            }
                        }
                        _statusMessage.value = null
                    } else {
                        retryAfter[translationKey] = SystemClock.elapsedRealtime() + 12000L
                        _statusMessage.value = if (cloudPreferred && !offlineReady)
                            "雲端翻譯未連線：請檢查 Worker 部署與 App 存取密鑰"
                        else "翻譯暫時失敗，請檢查網路或離線語言模型"
                    }
                    publish()
                }
            }
        }
        if (detected.isEmpty() && tracked.isEmpty() && pending.isEmpty()) {
            _statusMessage.value = "請對準英文、日文或中文字"
        }
        publish()
    }

    private fun publish() {
        // Keep a few complete, stable sentences instead of spraying a dozen
        // moving short OCR fragments over the whole camera preview.
        val latest = tracked.values
            .filter { it.seenCount >= TEXT_CONFIRM_FRAMES }
            .sortedBy { it.element.boundingBox?.top ?: Int.MAX_VALUE }
            .take(MAX_LIVE_SEGMENTS)
            .map { it.element }
        if (_textElements.value != latest) _textElements.value = latest
        if (latest.isEmpty() && pending.isEmpty()) _translationEngine.value = null
    }

    fun processBitmap(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val selectedSource = sourceLanguage
                val list = photoOcr.recognizeTextFromBitmap(bitmap, selectedSource)
                withContext(Dispatchers.Main) {
                    tracked.clear()
                    publishDetections(list)
                }
            } catch (e: Exception) {
                Log.w("CameraTranslator", "Photo OCR error", e)
                _statusMessage.value = "圖片辨識失敗"
            }
        }
    }

    fun saveToHistory(element: TextElement) {
        viewModelScope.launch {
            historyDao.insertHistory(
                TranslationHistory(
                    sourceLanguage = element.sourceLanguage,
                    originalText = element.originalText,
                    translatedText = element.translatedText.orEmpty()
                )
            )
        }
    }

    override fun onCleared() {
        translator.close()
        super.onCleared()
    }
}
