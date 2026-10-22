package com.example.cameratranslator.core

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.view.LifecycleCameraController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cameratranslator.core.models.TextElement
import com.example.cameratranslator.history.HistoryDao
import com.example.cameratranslator.history.TranslationHistory
import com.example.cameratranslator.ocr.MLKitOcrProvider
import com.example.cameratranslator.translation.MLKitTranslationProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val historyDao: HistoryDao
) : AndroidViewModel(application) {
    private val photoOcr = MLKitOcrProvider()
    private val translator = MLKitTranslationProvider()
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

    private data class Tracked(val element: TextElement, val lastSeenMs: Long)
    private val tracked = linkedMapOf<String, Tracked>()
    private val cache = mutableMapOf<String, String>()
    private val pending = mutableSetOf<String>()
    private val retryAfter = mutableMapOf<String, Long>()
    private val preferences = application.getSharedPreferences("translator_settings", 0)

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

    private fun key(e: TextElement): String =
        e.sourceLanguage + ":" + e.originalText.trim().replace(Regex("\\s+"), " ").lowercase()

    /** Called by CameraX on main executor. OCR must never wait for model downloads. */
    fun onCameraTextElements(elements: List<TextElement>) {
        if (!isPaused) publishDetections(elements)
    }

    private fun publishDetections(detected: List<TextElement>) {
        val now = SystemClock.elapsedRealtime()
        detected.forEach { element ->
            val id = key(element)
            val previous = tracked[id]?.element?.translatedText
            val cached = cache[id] ?: previous
            tracked[id] = Tracked(element.copy(translatedText = cached), now)

            // Downloads are managed in Settings, never from live camera frames.
            if (!translator.isReadyFor(element.sourceLanguage)) {
                _statusMessage.value = "離線模型尚未就緒，請到設定下載語言模型"
                return@forEach
            }

            if (cached.isNullOrBlank() && id !in pending && now >= (retryAfter[id] ?: 0L)) {
                pending.add(id)
                _statusMessage.value = null
                viewModelScope.launch(Dispatchers.IO) {
                    val translation = try {
                        translator.translate(element.originalText, element.sourceLanguage, "zh")
                    } catch (e: Exception) {
                        Log.w("CameraTranslator", "Translation failed", e)
                        null
                    }
                    withContext(Dispatchers.Main) {
                        pending.remove(id)
                        if (!translation.isNullOrBlank() && translation != element.originalText) {
                            cache[id] = translation
                            tracked[id]?.let { trackedItem ->
                                tracked[id] = trackedItem.copy(
                                    element = trackedItem.element.copy(translatedText = translation)
                                )
                            }
                            _statusMessage.value = null
                        } else {
                            retryAfter[id] = SystemClock.elapsedRealtime() + 12000L
                            _statusMessage.value = "翻譯暫時失敗，請調整鏡頭或至設定檢查模型"
                        }
                        publish()
                    }
                }
            }
        }

        // A momentary OCR miss must not cause translated boxes to flicker.
        val seen = detected.map { key(it) }.toSet()
        val iterator = tracked.iterator()
        while (iterator.hasNext()) {
            val (id, old) = iterator.next()
            if (id !in seen && now - old.lastSeenMs > 2400L) iterator.remove()
        }
        if (detected.isEmpty() && tracked.isEmpty() && pending.isEmpty()) {
            _statusMessage.value = "請對準英文或日文文字"
        }
        publish()
    }

    private fun publish() {
        _textElements.value = tracked.values.map { it.element }
    }

    fun processBitmap(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val list = photoOcr.recognizeTextFromBitmap(bitmap)
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
