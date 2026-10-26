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

    fun reliableEnglishCandidate(block: Block): Boolean {
        if (block.width < 20 || block.height < 12) return false
        // Very tall, nearly square English boxes are often 45-degree
        // diagonal stock-image watermarks rather than horizontal captions.
        // For a short word such as "pngtee"/"ongtree", avoid destructive
        // Chinese painting even when OCR confidence is misleadingly high.
        if (block.text.trim().length <= 12 &&
            block.height >= 60 &&
            block.height >= block.width * .55f) return false
        // Numeric-heavy event dates do not satisfy the ordinary 75% English
        // letter ratio, but an independently legible date/time is stronger
        // evidence than Tesseract's Thai-looking digit hallucinations.
        return reliableEnglish(block.text, block.confidence) ||
            (block.confidence >= .90f &&
                ThaiPhotoLinePolicy.translateEnglishEventDate(block.text) != null)
    }

    /** Same-baseline neighboring words become one phrase for Azure. */
    fun groupEnglish(blocks: List<Block>): List<Block> {
        val sorted = blocks.filter(::reliableEnglishCandidate)
            .sortedWith(compareBy<Block> { (it.top + it.bottom) / 2 }.thenBy { it.left })
        val visualRows = mutableListOf<MutableList<Block>>()
        for (block in sorted) {
            val row = visualRows.firstOrNull { existing ->
                val representative = existing.first()
                abs((block.top + block.bottom) - 
                    (representative.top + representative.bottom)) <=
                    max(block.height, representative.height) * .55f &&
                    min(block.bottom, representative.bottom) -
                        max(block.top, representative.top) >=
                        min(block.height, representative.height) * .45f
            }
            if (row == null) visualRows.add(mutableListOf(block))
            else row.add(block)
        }
        val result = mutableListOf<Block>()
        for (row in visualRows) {
            var cluster = mutableListOf<Block>()
            fun emit() {
                if (cluster.isEmpty()) return
                val group = cluster.toList()
                result.add(if (group.size == 1) group[0] else Block(
                    group.joinToString(" ") { it.text.trim() },
                    group.minOf { it.left }, group.minOf { it.top },
                    group.maxOf { it.right }, group.maxOf { it.bottom },
                    group.minOf { it.confidence }
                ))
                cluster = mutableListOf()
            }
            for (part in row.sortedBy { it.left }) {
                val last = cluster.lastOrNull()
                if (last != null &&
                    (part.left < last.right - 5 ||
                        part.left - last.right >
                            max(part.height, last.height) * 1.25f)) emit()
                cluster.add(part)
            }
            emit()
        }
        return result.sortedWith(compareBy<Block> { it.top }.thenBy { it.left })
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
