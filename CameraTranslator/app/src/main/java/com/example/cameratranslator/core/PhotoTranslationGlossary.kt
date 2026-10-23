package com.example.cameratranslator.core

import android.content.Context
import java.util.Locale

/**
 * Exact-word photo translation overrides.
 *
 * Azure Translator cannot infer enough context from a single printed title,
 * so single terms can be transliterated into Chinese. Keep a tiny, tested
 * built-in educational glossary. User-entered corrected translations are
 * remembered on-device for later photos, regardless of cloud/ML Kit engine.
 *
 * Never apply a partial-word substitution inside an ordinary sentence.
 */
internal object PhotoTranslationGlossary {
    fun builtin(original: String, source: String, target: String): String? {
        val key = original.trim().lowercase(Locale.ROOT)
        if (target != "zh" || source !in setOf("en", "ja", "auto")) return null
        return when (key) {
            "playbook" -> "遊戲手冊"
            else -> null
        }
    }

    fun key(original: String, source: String, target: String): String =
        source + "|" + target + "|" + original.trim().lowercase(Locale.ROOT)

    fun isValidCorrection(original: String, targetText: String): Boolean =
        original.isNotBlank() && original.length <= 1500 &&
            targetText.isNotBlank() && targetText.length <= 1500
}

/** Private editable word memory; no network, secrets or GitHub credentials. */
internal class PhotoTranslationMemory(context: Context) {
    private val store = context.getSharedPreferences("photo_translation_corrections", Context.MODE_PRIVATE)
    fun lookup(original: String, source: String, target: String): String? =
        store.getString(PhotoTranslationGlossary.key(original, source, target), null)
            ?.takeIf(String::isNotBlank)
            ?: PhotoTranslationGlossary.builtin(original, source, target)

    fun remember(original: String, source: String, target: String, translated: String): Boolean {
        if (!PhotoTranslationGlossary.isValidCorrection(original, translated)) return false
        return store.edit().putString(
            PhotoTranslationGlossary.key(original, source, target), translated.trim()
        ).commit()
    }
}
