package com.example.cameratranslator.camera

/** Accept readable sentences and short product names without trusting random OCR scraps. */
internal object OcrLineQuality {
    private val words = Regex("[A-Za-z]+(?:['-][A-Za-z]+)*")

    fun acceptEnglish(text: String): Boolean {
        val terms = words.findAll(text).map { it.value }.toList()
        val letters = terms.sumOf { term -> term.count(Char::isLetter) }
        if (terms.size < 2 || letters < 5) return false

        // Brand names such as "BENI BEAR", "Oral B" and "Beni Bear" are
        // genuine labels, not garbage merely because they have <13 letters.
        val allCaps = terms.all { term -> term.all { it.isUpperCase() } }
        val titleCase = terms.all { term -> term.first().isUpperCase() }
        if ((allCaps && letters >= 6) || (titleCase && letters >= 5)) return true

        // Short labels ending in a product number are also useful.
        if (text.any(Char::isDigit) && letters >= 6) return true

        // Sentences require more context so fragments ("We gray", "a dog")
        // are not translated as independently moving rectangles.
        return (terms.size >= 3 && letters >= 9) || letters >= 13
    }
}
