package com.example.cameratranslator.core

import kotlin.math.max
import kotlin.math.min

/**
 * Independent Japanese OCR cross-check. Paddle supplies confidence and ML Kit
 * supplies a second reading. A strong disagreement is NOT evidence that one
 * engine is always right: prefer a clean alternate only for low-confidence
 * Paddle, otherwise flag for manual review instead of painting guesses.
 *
 * Pure Kotlin: no photo text is uploaded, logged, or saved by this classifier.
 */
internal object PhotoJapaneseOcrConsensus {
    data class Block(
        val text: String, val left: Int, val top: Int,
        val right: Int, val bottom: Int, val confidence: Float? = null
    )
    enum class Action { KEEP, REPLACE, REVIEW }
    data class Decision(
        val action: Action,
        val similarity: Float? = null,
        val alternate: Block? = null
    )

    private val script = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff]")
    private val suspiciousLatin = Regex("[A-Za-z]{4,}")
    private fun norm(value: String): String =
        value.filterNot { it.isWhitespace() || it in "、。・，．!?！？（）()「」『』" }
            .lowercase()

    private fun scriptValid(value: String): Boolean =
        script.findAll(value).count() >= 2 &&
            !suspiciousLatin.containsMatchIn(value) && value.length in 2..180

    private fun overlap(a: Block, b: Block): Float {
        val width = min(a.right, b.right) - max(a.left, b.left)
        val height = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (width <= 0 || height <= 0) return 0f
        val small = min(
            (a.right - a.left).coerceAtLeast(1).toLong() *
                (a.bottom - a.top).coerceAtLeast(1),
            (b.right - b.left).coerceAtLeast(1).toLong() *
                (b.bottom - b.top).coerceAtLeast(1)
        )
        return width.toLong() * height / small.coerceAtLeast(1).toFloat()
    }

    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = min(
                    min(current[j] + 1, previous[j + 1] + 1),
                    previous[j] + if (a[i] == b[j]) 0 else 1
                )
            }
            current.copyInto(previous)
        }
        return 1f - previous[b.length] / max(a.length, b.length).toFloat()
    }

    fun evaluate(paddle: Block, mlkit: List<Block>): Decision {
        if (!scriptValid(paddle.text)) return Decision(Action.KEEP)
        val original = norm(paddle.text)
        val candidate = mlkit.asSequence()
            .filter { scriptValid(it.text) && overlap(paddle, it) >= 0.60f }
            // Don't confuse an ML Kit multi-line paragraph with one Paddle line.
            .filter {
                val size = norm(it.text).length
                size in (original.length * 0.65f).toInt().coerceAtLeast(2)..
                    (original.length * 1.45f).toInt().coerceAtLeast(2)
            }
            .maxByOrNull { overlap(paddle, it) }
            ?: return Decision(Action.KEEP)
        val score = similarity(original, norm(candidate.text))
        if (score >= 0.74f || original.length < 5) {
            return Decision(Action.KEEP, score, candidate)
        }
        // Disagreement in two sufficiently long readings: low-confidence
        // Paddle gets an independent ML Kit alternative; otherwise preserve
        // original pixels for inspection rather than trusting either guess.
        return if ((paddle.confidence ?: 1f) < 0.58f) {
            Decision(Action.REPLACE, score, candidate)
        } else Decision(Action.REVIEW, score, candidate)
    }
}
