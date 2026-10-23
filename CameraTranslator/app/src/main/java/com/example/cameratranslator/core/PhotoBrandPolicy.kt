package com.example.cameratranslator.core

/** Preserve real Latin product marks on non-English packaging without
 * misclassifying an entire English slogan as a logo.
 */
internal object PhotoBrandPolicy {
    fun shouldPreserve(text: String, sourceLanguage: String): Boolean {
        if (sourceLanguage == "en" || sourceLanguage == "th") return false
        val words = text.trim().split(Regex("\\s+"))
        if (words.size !in 2..4) return false
        if (!text.all { it.isLetter() || it.isWhitespace() || it in "-®©.&" }) return false
        val letters = text.filter { it.isLetter() }
        if (letters.length !in 4..48 || letters.any {
                it !in 'a'..'z' && it !in 'A'..'Z'
            }) return false
        if (letters.all(Char::isUpperCase)) return true
        // Ordinary mixed-case sentences, e.g. "Create your own World",
        // are NOT protected as trademarks.
        return words.size <= 3 && words.all { word ->
            val name = word.trim('-', '.', '&', '®', '©')
            name.length > 1 && name.first().isUpperCase() &&
                name.drop(1).all(Char::isLowerCase)
        }
    }
}
