package com.example.cameratranslator.ocr

/** Language/script routing shared by photo OCR and unit tests. */
internal object PhotoOcrLanguages {
    private val photoSources = setOf("auto", "en", "ja", "zh", "th")
    fun supported(source: String): Boolean = source in photoSources
    fun containsThai(text: String): Boolean =
        text.any { it in '\u0E01'..'\u0E5B' }
    /**
     * Japanese/Chinese OCR also sees Latin product logos. Do not label
     * "Playbook" as Japanese only because the user chose Japanese for
     * the rest of the photo. Each block gets its actual script.
     */
    fun resolveBlockSource(text: String, forced: String?, scriptDetected: String): String {
        val letters = text.filter(Char::isLetter)
        if (forced in setOf("ja", "zh") && letters.isNotEmpty() &&
            letters.all { it in 'A'..'Z' || it in 'a'..'z' }
        ) return "en"
        return forced ?: scriptDetected
    }
}
