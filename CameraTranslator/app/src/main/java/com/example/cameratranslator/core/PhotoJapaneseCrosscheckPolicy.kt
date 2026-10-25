package com.example.cameratranslator.core

/**
 * Run the second Japanese OCR reader for manually selected Japanese photos,
 * or for Auto photos whose Paddle results predominantly contain Japanese.
 * Never add an expensive second recognition pass to a mostly non-Japanese photo.
 */
internal object PhotoJapaneseCrosscheckPolicy {
    fun shouldCrosscheck(requestedSource: String, blockSources: List<String>): Boolean {
        if (requestedSource == "ja") return true
        if (requestedSource != "auto") return false
        val japanese = blockSources.count { it == "ja" }
        val known = blockSources.count { it in setOf("ja", "en", "zh", "th") }
        return japanese >= 2 && japanese * 2 >= known
    }
}
