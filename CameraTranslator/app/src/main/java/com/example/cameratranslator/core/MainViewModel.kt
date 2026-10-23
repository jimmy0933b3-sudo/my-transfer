package com.example.cameratranslator.core

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.Bitmap
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
    private val photoOcr = MLKitOcrProvider()
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
    private val preferences = application.getSharedPreferences("translator_settings", 0)

    var translationMode by mutableStateOf(preferences.getString("translation_mode", "auto") ?: "auto")
        private set
    var cloudTokenConfigured by mutableStateOf(cloudCredentials.load() != null)
        private set

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
            cache.clear()
            cacheEngine.clear()
            resetRecognitions()
        }
        return saved
    }

    fun clearCloudToken() {
        cloudCredentials.clear()
        cloudTokenConfigured = false
        cache.clear()
        cacheEngine.clear()
        resetRecognitions()
    }

    private fun shouldTryCloud(): Boolean {
        if (translationMode == "offline" || !cloudTokenConfigured) return false
        if (translationMode == "cloud") return true
        val manager = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false)
            ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }


    companion object {
        // Updating the UI for every OCR frame magnifies harmless recognition noise.
        private const val SAMPLE_INTERVAL_MS = 420L
        private const val LOST_TRACK_GRACE_MS = 1450L
        private const val TEXT_CONFIRM_FRAMES = 3
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
            if (overlap < 0.30f && !(sameText && distance < scale * 0.5f)) {
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
                _statusMessage.value = if (cloudTokenConfigured && translationMode == "auto")
                    "目前使用行動網路；自動模式需要 Wi-Fi，或請在設定改為雲端優先"
                else "離線模型尚未就緒，請到設定下載語言模型"
                continue
            }
            if (translationKey in pending || pending.size >= 3 ||
                now < (retryAfter[translationKey] ?: 0L)) continue
            pending.add(translationKey)
            _statusMessage.value = null
            val requestedTarget = targetLanguage
            val requestedRevision = languageRevision
            viewModelScope.launch(Dispatchers.IO) {
                var actualEngine: String? = null
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
                        val offlineResult = translator.translate(
                            element.originalText, element.sourceLanguage, requestedTarget
                        )
                        if (!offlineResult.isNullOrBlank()) actualEngine = "ML Kit 離線"
                        offlineResult
                    } else null
                } catch (e: Exception) {
                    Log.w("CameraTranslator", "Translation failed", e)
                    null
                }
                withContext(Dispatchers.Main) {
                    pending.remove(translationKey)
                    if (requestedRevision != languageRevision) return@withContext
                    if (!translated.isNullOrBlank() && translated != element.originalText) {
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
        val latest = tracked.values.map { it.element }
        if (_textElements.value != latest) _textElements.value = latest
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
