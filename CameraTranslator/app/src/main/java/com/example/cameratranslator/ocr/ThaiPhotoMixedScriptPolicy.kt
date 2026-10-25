package com.example.cameratranslator.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A photo can contain Thai and independently printed English copy. Never
 * route a reliable Latin-only Paddle block through a Thai translator.
 *
 * These are geometry/script rules, not ad- or screenshot-specific keywords.
 * Keep actual image rectangles so the renderer never paints a wide paragraph
 * across separate labels or the picture.
 */
internal object ThaiPhotoMixedScriptPolicy {
    data class Block(
        val text: String, val left: Int, val top: Int,
        val right: Int, val bottom: Int, val confidence: Float
    ) {
        val width get() = right - left
        val height get() = bottom - top
    }

    private val latinWord = Regex("[A-Za-z]{3,}")

    fun reliableEnglish(text: String, confidence: Float): Boolean {
        val clean = text.trim()
        if (confidence < .89f || clean.length !in 4..120) return false
        if (ThaiPhotoLinePolicy.isBrowserUrl(clean) ||
            clean.contains("://") || clean.contains('@')) return false
        if (clean.any { it in '\u0e00'..'\u0e7f' }) return false
        val words = latinWord.findAll(clean).toList()
        if (words.isEmpty()) return false
        val letters = clean.count(Char::isLetter)
        if (letters < 4 || clean.count { it in 'A'..'Z' || it in 'a'..'z' } != letters)
            return false
        // A standalone status indicator such as "00:34" must not become
        // a synthetic English translation just because OCR is confident.
        return letters * 4 >= clean.length * 3 &&
            clean.none { it in "[]{}<>|/#=" }
    }

    fun reliableEnglishCandidate(block: Block): Boolean =
        block.width >= 20 && block.height >= 12 &&
            reliableEnglish(block.text, block.confidence)

    /** Same-baseline neighboring words become one phrase for Azure. */
    fun groupEnglish(blocks: List<Block>): List<Block> {
        val sorted = blocks.filter(::reliableEnglishCandidate)
            .sortedWith(compareBy<Block> { it.top }.thenBy { it.left })
        val rows = mutableListOf<MutableList<Block>>()
        for (block in sorted) {
            val row = rows.lastOrNull()
            val last = row?.lastOrNull()
            if (row != null && last != null &&
                abs((block.top + block.bottom) - (last.top + last.bottom)) <=
                    max(block.height, last.height) * .5f &&
                block.left >= last.right - 5 &&
                block.left - last.right <= max(block.height, last.height) * 1.25f) {
                row.add(block)
            } else rows.add(mutableListOf(block))
        }
        return rows.map { row ->
            if (row.size == 1) row[0] else Block(
                row.joinToString(" ") { it.text.trim() },
                row.minOf { it.left }, row.minOf { it.top },
                row.maxOf { it.right }, row.maxOf { it.bottom },
                row.minOf { it.confidence }
            )
        }
    }

    /** Independent clear English glyphs can veto same-area Thai hallucinations.
     * Requires English coverage of most of the Thai box, not a single letter
     * from a neighboring column or an English logo printed beside Thai.
     */
    fun isLatinHallucination(thai: Block, english: List<Block>): Boolean {
        if (thai.width <= 0 || thai.height <= 0) return false
        val area = thai.width.toLong() * thai.height
        val covered = english.sumOf { en ->
            (min(thai.right, en.right) - max(thai.left, en.left))
                .coerceAtLeast(0).toLong() *
                (min(thai.bottom, en.bottom) - max(thai.top, en.top))
                    .coerceAtLeast(0)
        }.coerceAtMost(area)
        return covered >= area * .55f
    }
}
