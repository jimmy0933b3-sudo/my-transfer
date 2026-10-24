package com.example.cameratranslator.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Photo-only OCR postprocessing. ML Kit emits blocks, not semantic sentences.
 * Merge nearby, vertically stacked blocks that look like one paragraph; do not
 * merge unrelated columns or separately numbered sentences.
 *
 * Pure Kotlin so sentence geometry and digit-correction rules can be unit tested.
 */
internal object PhotoOcrPostProcessor {
    data class Segment(
        val text: String,
        val language: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val originalIndices: List<Int>,
        val lineCount: Int = (text.count { it == '\n' } + 1).coerceAtLeast(1)
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val lineHeight: Float get() =
            height.toFloat() / lineCount.coerceAtLeast(1)
    }

    /** Never use a global 0 -> O replacement: years, quantities and codes must survive. */
    fun repairLatinOZero(value: String): String =
        Regex("[A-Za-z0-9]+").replace(value) { match ->
            val token = match.value
            val letterCount = token.count { it.isLetter() }
            if (letterCount < 2 || !token.any { it.isLowerCase() } ||
                token.any { it.isDigit() && it != '0' }) token
            else token.replace('0', 'o')
        }

    fun combine(blocks: List<Segment>): List<Segment> {
        if (blocks.isEmpty()) return emptyList()
        val ordered = blocks.sortedWith(compareBy<Segment> { it.top }.thenBy { it.left })
        val merged = mutableListOf<Segment>()
        for (next in ordered) {
            // Search backwards for nearby stacked blocks, not only the last
            // item: multiple columns on the same row may be interleaved.
            val matchIndex = merged.indices.reversed().firstOrNull {
                canMerge(merged[it], next)
            }
            if (matchIndex == null) {
                merged.add(next.copy(text = clean(next.text, next.language)))
                continue
            }
            val previous = merged[matchIndex]
            val joining = if (next.language == "en") " " else "\n"
            merged[matchIndex] = previous.copy(
                text = previous.text.trimEnd() + joining + clean(next.text, next.language).trimStart(),
                left = min(previous.left, next.left),
                top = min(previous.top, next.top),
                right = max(previous.right, next.right),
                bottom = max(previous.bottom, next.bottom),
                originalIndices = previous.originalIndices + next.originalIndices,
                lineCount = previous.lineCount + next.lineCount
            )
        }
        return merged.sortedWith(compareBy<Segment> { it.top }.thenBy { it.left })
    }

    private fun clean(text: String, language: String): String =
        if (language == "en") repairLatinOZero(text).replace(Regex("\\s+"), " ").trim()
        else text

    private fun canMerge(a: Segment, b: Segment): Boolean {
        if (a.language != b.language || a.width <= 0 || b.width <= 0 ||
            a.height <= 0 || b.height <= 0) return false
        val previous = a.text.trimEnd()
        val incoming = b.text.trimStart()
        if (previous.isEmpty() || incoming.isEmpty()) return false
        if (previous.last() in ".!?。！？:" || incoming.first() in "•-") return false
        // The Katakana-only collection-sheet TITLE is not a continuation
        // of a smaller Japanese instruction underneath it. Joining them
        // destroyed context and produced gibberish in the user's photo.
        if (a.language == "ja" &&
            Regex("^[\\u30a0-\\u30ffー・\\s]{4,24}$").matches(previous)) {
            return false
        }
        if (Regex("^\\d{1,3}\\s*[.)、:]").containsMatchIn(incoming)) return false

        val gap = b.top - a.bottom
        val lineHeight = min(a.lineHeight, b.lineHeight).coerceAtLeast(1f)
        if (gap < -lineHeight * 0.40f || gap > max(7f, lineHeight * 1.1f)) return false

        val horizontalOverlap = max(0, min(a.right, b.right) - max(a.left, b.left))
        val minWidth = min(a.width, b.width).coerceAtLeast(1)
        val centersAligned = abs(a.centerX - b.centerX) <= max(a.width, b.width) * 0.28f
        if (horizontalOverlap < minWidth * 0.40f || !centersAligned) return false

        // Blocks with wildly different text scale are usually different
        // labels (e.g. product name above a tiny legal disclaimer).
        val sizeRatio = max(a.lineHeight, b.lineHeight) / lineHeight
        if (sizeRatio > 1.55f) return false

        // Require at least one unfinished sentence on English; title-case
        // visual labels still qualify, but punctuation-delimited lines don't.
        return true
    }
}
