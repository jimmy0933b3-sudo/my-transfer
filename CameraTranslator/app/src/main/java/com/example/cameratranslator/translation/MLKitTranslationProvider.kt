package com.example.cameratranslator.translation

import android.icu.text.Transliterator
import android.os.Build
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

class MLKitTranslationProvider : TranslationProvider {
    private data class Entry(val client: Translator, var modelReady: Boolean = false)
    private val entries = mutableMapOf<String, Entry>()
    private val mutex = Mutex()

    override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): String? {
        if (text.isBlank()) return null
        val source = code(sourceLanguage) ?: return null
        val target = code(targetLanguage) ?: return null
        if (source == target) return null

        return try {
            val entry = mutex.withLock {
                val key = source + ":" + target
                val e = entries.getOrPut(key) {
                    val options = TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(target)
                        .build()
                    Entry(Translation.getClient(options))
                }
                if (!e.modelReady) {
                    e.client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                    e.modelReady = true
                }
                e
            }
            val output = entry.client.translate(text).await().takeIf { it.isNotBlank() } ?: return null
            if (target == TranslateLanguage.CHINESE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    Transliterator.getInstance("Simplified-Traditional").transliterate(output)
                } catch (e: Exception) {
                    Log.w("CameraTranslator", "Chinese script conversion unavailable", e)
                    output
                }
            } else output
        } catch (e: Exception) {
            Log.w("CameraTranslator", "Translation or model download failed", e)
            null
        }
    }

    override suspend fun downloadModelIfNeeded(languageCode: String) {
        // Model download is source-target pair-specific, handled by translate().
    }

    fun close() {
        entries.values.forEach { it.client.close() }
        entries.clear()
    }

    private fun code(code: String): String? = when (code.lowercase()) {
        "en", "english" -> TranslateLanguage.ENGLISH
        "ja", "japanese" -> TranslateLanguage.JAPANESE
        "th", "thai" -> TranslateLanguage.THAI
        "zh", "zh-tw", "chinese" -> TranslateLanguage.CHINESE
        else -> null
    }
}
