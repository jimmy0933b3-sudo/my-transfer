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


    /**
     * ML Kit may split one Paddle text line into two adjacent blocks.
     * Build geometric reading candidates only for horizontal, same-row
     * neighbors. Synthetic readings are supporting evidence, not independent
     * votes, and must pass the same similarity / confidence safety gates.
     */
    private fun joinedSameRow(paddle: Block, blocks: List<Block>): List<Block> {
        val eligible = blocks.filter { scriptValid(it.text) && it.bottom > paddle.top &&
            it.top < paddle.bottom }
        val joined = mutableListOf<Block>()
        for (a in eligible) for (b in eligible) {
            if (a === b || a.left >= b.left) continue
            val vertical = min(a.bottom, b.bottom) - max(a.top, b.top)
            val minHeight = min(a.bottom - a.top, b.bottom - b.top).coerceAtLeast(1)
            val gap = b.left - a.right
            if (vertical.toFloat() / minHeight < .68f ||
                gap < -8 || gap > max(22, (paddle.right - paddle.left) / 8)) continue
            val combined = Block(
                a.text.trimEnd() + b.text.trimStart(),
                a.left, min(a.top, b.top), b.right, max(a.bottom, b.bottom)
            )
            if (scriptValid(combined.text) &&
                intersectionOverUnion(paddle, combined) >= .48f) joined.add(combined)
        }
        return joined
    }

    /**
     * ML Kit also groups several Japanese lines into one large paragraph.
     * An enclosing paragraph can corroborate a contained Paddle line, but
     * MUST NOT replace its text or coordinates: ML Kit's reading also
     * contains other lines and sometimes furigana.
     */
    private fun paragraphEvidence(paddle: Block, blocks: List<Block>): Decision? {
        val original = norm(paddle.text)
        if (original.length < 5) return null
        return blocks.asSequence().filter { scriptValid(it.text) }
            .filter { norm(it.text).length >= original.length * 1.6f }
            .mapNotNull { parent ->
                val w = min(parent.right, paddle.right) - max(parent.left, paddle.left)
                val h = min(parent.bottom, paddle.bottom) - max(parent.top, paddle.top)
                val paddleArea = (paddle.right - paddle.left).coerceAtLeast(1).toLong() *
                    (paddle.bottom - paddle.top).coerceAtLeast(1)
                if (w <= 0 || h <= 0 || w.toLong() * h / paddleArea.toFloat() < .72f) {
                    return@mapNotNull null
                }
                val full = norm(parent.text)
                // A matching substring is strong evidence, but a random
                // short common phrase in a nearby paragraph is not.
                val score = if (full.contains(original)) 1f else {
                    var best = 0f
                    for (length in (original.length - 2).coerceAtLeast(5)..
                        min(full.length, original.length + 2)) {
                        for (start in 0..full.length - length) {
                            best = max(best, similarity(original,
                                full.substring(start, start + length)))
                        }
                    }
                    best
                }
                if (score >= .90f) Decision(
                    Action.KEEP, score, parent,
                    "parent_paragraph_corroboration",
                    intersectionOverUnion(paddle, parent)
                ) else null
            }.maxByOrNull { it.similarity ?: 0f }
    }

    fun evaluate(paddle: Block, mlkit: List<Block>): Decision {
        if (!scriptValid(paddle.text)) return Decision(Action.KEEP, reason = "not_japanese")
        val original = norm(paddle.text)
        val candidates = (mlkit + joinedSameRow(paddle, mlkit)).asSequence()
            .filter { scriptValid(it.text) }
            .map { it to intersectionOverUnion(paddle, it) }
            .filter { it.second >= 0.48f }
            .filter {
                val len = norm(it.first.text).length
                len in (original.length * 0.65f).toInt().coerceAtLeast(2)..
                    (original.length * 1.45f).toInt().coerceAtLeast(2)
            }
            .toList()
        val mostOverlapping = candidates.maxByOrNull { it.second }
            ?: return paragraphEvidence(paddle, mlkit) ?: Decision(Action.KEEP, reason = "no_aligned_match")

        // A marginally lower-IoU block may be the actual text line, while
        // the highest IoU is ruby or an adjacent annotation. Require BOTH
        // strong geometric alignment and close reading before preferring it.
        val corroborated = candidates
            .filter { it.second >= .68f &&
                it.second >= mostOverlapping.second - .08f }
            .maxByOrNull { similarity(original, norm(it.first.text)) }
            ?.takeIf { similarity(original, norm(it.first.text)) >= .84f }
        val candidate = corroborated ?: mostOverlapping
        val alternate = candidate.first
        val iou = candidate.second
        val score = similarity(original, norm(alternate.text))
        val confidence = paddle.confidence ?: 1f
        // Competing, equally positioned ML Kit lines are ambiguous. With
        // weak Paddle and a non-identical candidate, do not rewrite pixels.
        val conflicting = candidates.any {
            it.first !== alternate && it.second >= iou - .08f &&
                similarity(original, norm(it.first.text)) < .74f
        }
        if (confidence < .42f && score < 1f && conflicting) {
            return Decision(Action.REVIEW, score, alternate,
                "conflicting_alternates", iou)
        }
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
        // Weak, geometrically aligned but strongly contradictory readings
        // are unsafe to paint automatically, even if Paddle is not below .42.
        if (confidence < .65f && score < .35f && iou >= .72f) {
            return Decision(Action.REVIEW, score, alternate,
                "moderate_confidence_severe_disagreement", iou)
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
