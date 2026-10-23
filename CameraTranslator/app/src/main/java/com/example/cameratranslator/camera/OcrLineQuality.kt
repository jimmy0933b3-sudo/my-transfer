package com.example.cameratranslator.camera

/** Lightweight guard against translating fragmentary Latin OCR from a moving camera. */
internal object OcrLineQuality {
    private val words = Regex("[A-Za-z]+(?:['-][A-Za-z]+)*")

    fun acceptEnglish(text: String): Boolean {
        val terms = words.findAll(text).map { it.value }.toList()
        val letters = terms.sumOf { it.count(Char::isLetter) }
        if (terms.size < 2 || letters < 9) return false
        // Two very short words floating independently in a busy frame are
        // often not meaningful OCR. Retain headings with digits or long words.
        if (terms.size == 2 && letters < 13 &&
            !text.any(Char::isDigit)) return false
        return true
    }
}
