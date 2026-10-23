package com.example.cameratranslator.core

/**
 * Pure functions shared by high-resolution photo OCR and language indicators.
 * Do not assume that every OCR block on a Japanese box is Japanese:
 * English logos and Chinese labels may appear on the same image.
 */
internal object SnapshotTextPolicy {
    fun isLatinBrand(text: String): Boolean {
        val trimmed = text.trim()
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size !in 2..5) return false
        val letters = trimmed.filter { it.isLetter() }
        if (letters.length < 4 || letters.any { it !in 'A'..'Z' && it !in 'a'..'z' }) return false
        if (!trimmed.all { it.isLetterOrDigit() || it.isWhitespace() || it in "-®©.&" }) return false
        // Preserve uppercase branding, but don't exempt whole normal English
        // sentences from translation just because they contain several words.
        return letters.all { it.isUpperCase() }
    }

    fun displayLanguage(code: String): String = when (code) {
        "en" -> "英文"
        "ja" -> "日文"
        "zh" -> "中文"
        "th" -> "泰文"
        "auto" -> "自動"
        else -> "原文"
    }

    fun containsThai(text: String): Boolean = text.any { it in '\u0e00'..'\u0e7f' }

    fun sourceForText(text: String, recognizerLanguage: String): String {
        // Latin-only text inside a Japanese OCR block is English, not Japanese.
        val trimmed = text.trim()
        val letters = trimmed.filter { it.isLetter() }
        if (letters.isNotEmpty() &&
            letters.all { it in 'A'..'Z' || it in 'a'..'z' } &&
            recognizerLanguage == "ja"
        ) return "en"
        return recognizerLanguage
    }
}
