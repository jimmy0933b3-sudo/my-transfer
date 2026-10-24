package com.example.cameratranslator.core

/**
 * Conservative OCR line-geometry orientation voting.
 * Pure Kotlin so tricky rotation decisions can be unit-tested.
 */
internal object PhotoOrientationRanking {
    data class Evidence(
        val readableCharacters: Int,
        val horizontalLines: Int,
        val verticalLines: Int
    ) {
        val score: Float get() =
            (readableCharacters.coerceAtMost(400) * 0.3f +
                horizontalLines * 4f - verticalLines * 2f).coerceAtLeast(0f)
    }

    fun choose(scores: Map<Int, Evidence>): Int {
        val baseline = scores[0] ?: return 0
        val strongest = scores.entries.maxByOrNull { it.value.score } ?: return 0
        if (strongest.key == 0 || strongest.key !in setOf(90, 270)) return 0
        if (strongest.value.horizontalLines < 2 ||
            strongest.value.readableCharacters < 10) return 0
        if (strongest.value.score < baseline.score * 1.4f + 8f) return 0
        return strongest.key
    }
}
