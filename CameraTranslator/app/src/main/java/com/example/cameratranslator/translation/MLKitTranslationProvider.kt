package com.example.cameratranslator.translation

import android.icu.text.Transliterator
import android.os.Build
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

enum class ModelState { NOT_DOWNLOADED, DOWNLOADING, READY, FAILED }

data class ModelStatus(
    val state: ModelState = ModelState.NOT_DOWNLOADED,
    val detail: String = "尚未下載"
)

/**
 * ML Kit Android exposes the model's completion state, but does not expose a
 * byte-download percentage. Never render a fabricated progress percentage.
 *
 * RemoteModelManager.download() reuses an existing in-progress download Task.
 * Reinvoking it after a network interruption provides a retry/resume entry point.
 */
class MLKitTranslationProvider : TranslationProvider {
    companion object {
        val supportedLanguages = listOf("zh", "en", "ja", "th")
    }

    private val modelManager = RemoteModelManager.getInstance()
    private val statusFlow = MutableStateFlow(
        supportedLanguages.associateWith { ModelStatus() }
    )
    val modelStatuses: StateFlow<Map<String, ModelStatus>> = statusFlow.asStateFlow()
    private val downloadLocks = supportedLanguages.associateWith { Mutex() }
    private val translatorLock = Mutex()
    private val translators = mutableMapOf<String, Translator>()
    private val readyPairs = mutableSetOf<String>()

    private fun model(language: String) = TranslateRemoteModel.Builder(language).build()

    private fun setModelStatus(language: String, state: ModelState, detail: String) {
        statusFlow.update { it + (language to ModelStatus(state, detail)) }
    }

    fun isReadyFor(sourceLanguage: String): Boolean {
        val source = code(sourceLanguage) ?: return false
        return modelStatuses.value[source]?.state == ModelState.READY &&
            modelStatuses.value["zh"]?.state == ModelState.READY
    }

    suspend fun refreshModelStatuses() {
        try {
            val downloaded = modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
                .await().map { it.language }.toSet()
            for (language in supportedLanguages) {
                if (language in downloaded) {
                    setModelStatus(language, ModelState.READY, "已下載，可離線翻譯")
                } else if (statusFlow.value[language]?.state != ModelState.DOWNLOADING) {
                    setModelStatus(language, ModelState.NOT_DOWNLOADED, "尚未下載")
                }
            }
        } catch (e: Exception) {
            Log.w("CameraTranslator", "Failed to query translation models", e)
            for (language in supportedLanguages) {
                if (statusFlow.value[language]?.state != ModelState.DOWNLOADING) {
                    setModelStatus(language, ModelState.FAILED, "無法檢查模型，請重試")
                }
            }
        }
    }

    suspend fun downloadLanguage(languageCode: String, wifiOnly: Boolean): Boolean {
        val language = code(languageCode) ?: return false
        val lock = downloadLocks[language] ?: return false
        return lock.withLock {
            if (statusFlow.value[language]?.state == ModelState.READY) return@withLock true
            val conditions = DownloadConditions.Builder().apply {
                if (wifiOnly) requireWifi()
            }.build()
            setModelStatus(
                language, ModelState.DOWNLOADING,
                if (wifiOnly) "下載中（僅 Wi-Fi），可以返回相機" else "下載中，可以返回相機"
            )
            try {
                // The underlying ML Kit Task is managed by its SDK, rather than a custom
                // downloader. Retrying calls download() again with the same model.
                withTimeout(180_000L) {
                    modelManager.download(model(language), conditions).await()
                }
                setModelStatus(language, ModelState.READY, "已下載，可離線翻譯")
                true
            } catch (e: TimeoutCancellationException) {
                Log.w("CameraTranslator", "Model download timeout for " + language, e)
                setModelStatus(language, ModelState.FAILED, "下載逾時，點選繼續下載")
                false
            } catch (e: CancellationException) {
                setModelStatus(language, ModelState.FAILED, "下載中斷，點選繼續下載")
                throw e
            } catch (e: Exception) {
                Log.w("CameraTranslator", "Model download failed for " + language, e)
                setModelStatus(language, ModelState.FAILED, "下載未完成，檢查網路後點選繼續下載")
                false
            }
        }
    }

    override suspend fun downloadModelIfNeeded(languageCode: String) {
        downloadLanguage(languageCode, true)
    }

    override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): String? {
        if (text.isBlank()) return null
        val source = code(sourceLanguage) ?: return null
        val target = code(targetLanguage) ?: return null
        if (source == target || !isReadyFor(source)) return null

        try {
            val client = translatorLock.withLock {
                val pair = source + ":" + target
                val translator = translators.getOrPut(pair) {
                    Translation.getClient(
                        TranslatorOptions.Builder()
                            .setSourceLanguage(source)
                            .setTargetLanguage(target)
                            .build()
                    )
                }
                if (pair !in readyPairs) {
                    // Both models were explicitly installed in Settings; ensure the
                    // Translator knows they're available before translating.
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                    readyPairs.add(pair)
                }
                translator
            }
            val result = client.translate(text).await().takeIf { it.isNotBlank() } ?: return null
            return if (target == TranslateLanguage.CHINESE &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    Transliterator.getInstance("Simplified-Traditional").transliterate(result)
                } catch (e: Exception) {
                    Log.w("CameraTranslator", "Traditional Chinese conversion unavailable", e)
                    result
                }
            } else result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("CameraTranslator", "Translation failed", e)
            return null
        }
    }

    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }

    private fun code(code: String): String? = when (code.lowercase()) {
        "en", "english" -> TranslateLanguage.ENGLISH
        "ja", "japanese" -> TranslateLanguage.JAPANESE
        "th", "thai" -> TranslateLanguage.THAI
        "zh", "zh-tw", "chinese" -> TranslateLanguage.CHINESE
        else -> null
    }
}
