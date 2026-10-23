package com.example.cameratranslator.ocr

/** Language/script routing shared by photo OCR and unit tests. */
internal object PhotoOcrLanguages {
    private val photoSources = setOf("auto", "en", "ja", "zh", "th")
    fun supported(source: String): Boolean = source in photoSources
    fun containsThai(text: String): Boolean =
        text.any { it in '\u0E01'..'\u0E5B' }
}
