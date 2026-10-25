package com.example.cameratranslator.core

import kotlin.math.max
import kotlin.math.min

/**
 * Independent Japanese OCR readings are supporting evidence, not a vote.
 *
 * On product sheets, furigana and rotated labels lead to different line
 * segmentation: a smaller ML Kit annotation often overlaps a larger Paddle
 * sentence. We must never reject or replace a confident main-text reading
 * solely because a partially overlapping alternate has different characters.
 *
 * Pure Kotlin, with no image or OCR text persisted by this classifier.
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
        val alternate: Block? = null,
        val reason: String = "no_aligned_match",
        val intersectionOverUnion: Float? = null
    )

    private val script = Regex("[\u3040-\u30ff\u3400-\u9fff]")
    private val suspiciousLatin = Regex("[A-Za-z]{4,}")
    private fun norm(value: String): String =
        value.filterNot { it.isWhitespace() || it in "、。・，．!?！？（）()「」『』" }
            .lowercase()

    private fun scriptValid(value: String): Boolean =
        script.findAll(value).count() >= 2 &&
            !suspiciousLatin.containsMatchIn(value) && value.length in 2..180

    /**
     * IoU (rather than intersection / smaller-area) prevents matching the
     * furigana or a short line INSIDE a full printed Japanese paragraph.
     */
    private fun intersectionOverUnion(a: Block, b: Block): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0 || h <= 0) return 0f
        val areaA = (a.right - a.left).coerceAtLeast(1).toLong() *
            (a.bottom - a.top).coerceAtLeast(1)
        val areaB = (b.right - b.left).coerceAtLeast(1).toLong() *
            (b.bottom - b.top).coerceAtLeast(1)
        val intersection = w.toLong() * h
        return intersection / (areaA + areaB - intersection).coerceAtLeast(1).toFloat()
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
        if (!scriptValid(paddle.text)) return Decision(Action.KEEP, reason = "not_japanese")
        val original = norm(paddle.text)
        val candidate = mlkit.asSequence()
            .filter { scriptValid(it.text) }
            .map { it to intersectionOverUnion(paddle, it) }
            .filter { it.second >= 0.48f }
            .filter {
                val len = norm(it.first.text).length
                len in (original.length * 0.65f).toInt().coerceAtLeast(2)..
                    (original.length * 1.45f).toInt().coerceAtLeast(2)
            }
            .maxByOrNull { it.second }
            ?: return Decision(Action.KEEP, reason = "no_aligned_match")

        val alternate = candidate.first
        val iou = candidate.second
        val score = similarity(original, norm(alternate.text))
        val confidence = paddle.confidence ?: 1f
        if (score >= .74f || original.length < 5) {
            // Accept corrections only when Paddle itself is genuinely
            // uncertain and the readings almost agree. Strong Paddle keeps
            // its own original typography even if the alternative differs.
            if (confidence < .42f && original.length >= 5 && score >= .84f &&
                original != norm(alternate.text) && iou >= .68f) {
                return Decision(Action.REPLACE, score, alternate,
                    "low_confidence_close_match", iou)
            }
            return Decision(Action.KEEP, score, alternate, "readings_agree", iou)
        }
        // OCR disagreements are not proof of error. On this toy collection
        // sheet, 7-8 confident Japanese vehicle names were incorrectly
        // blocked simply because ML Kit included ruby text or different lines.
        if (confidence >= .42f) {
            return Decision(Action.KEEP, score, alternate,
                "disagreement_unverified", iou)
        }
        // Very weak Paddle AND a closely aligned disagreeing reader are
        // genuinely ambiguous. Leave the pixels unchanged instead of
        // replacing them with an unrelated Japanese phrase.
        return Decision(Action.REVIEW, score, alternate,
            "low_confidence_disagreement", iou)
    }
}
