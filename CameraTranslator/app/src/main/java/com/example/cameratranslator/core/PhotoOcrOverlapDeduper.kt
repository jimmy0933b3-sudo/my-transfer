package com.example.cameratranslator.core

import com.example.cameratranslator.core.models.TextElement
import kotlin.math.max
import kotlin.math.min

/**
 * Remove only exact-text duplicate OCR rectangles. Two neighboring sentences
 * may overlap geometrically, so overlap alone is never grounds for deletion.
 */
internal object PhotoOcrOverlapDeduper {
    data class Block(
        val text: String, val source: String, val left: Int,
        val top: Int, val right: Int, val bottom: Int,
        val confidence: Float? = null
    )
    private fun key(text: String): String = text
        .filterNot { it.isWhitespace() || it in "。．、，!！?？" }
        .lowercase()

    fun selectedIndices(lines: List<Block>): List<Int> {
        val kept = mutableListOf<Int>()
        lines.forEachIndexed { index, block ->
            val match = kept.indexOfFirst { previous ->
                val other = lines[previous]
                if (block.source != other.source ||
                    key(block.text) != key(other.text)) false
                else {
                    val width = min(block.right, other.right) -
                        max(block.left, other.left)
                    val height = min(block.bottom, other.bottom) -
                        max(block.top, other.top)
                    val small = min(
                        (block.right - block.left).toLong() *
                            (block.bottom - block.top),
                        (other.right - other.left).toLong() *
                            (other.bottom - other.top)
                    )
                    width > 0 && height > 0 && small > 0 &&
                        width.toLong() * height / small.toFloat() >= .75f
                }
            }
            if (match < 0) kept.add(index)
            else if ((block.confidence ?: 0f) >
                (lines[kept[match]].confidence ?: 0f)) kept[match] = index
        }
        return kept
    }

    fun deduplicate(lines: List<TextElement>): List<TextElement> {
        // Elements with no bounds survive (the renderer diagnoses them).
        val withBounds = lines.mapIndexedNotNull { index, item ->
            val bounds = item.boundingBox ?: return@mapIndexedNotNull null
            index to Block(item.originalText, item.sourceLanguage,
                bounds.left, bounds.top, bounds.right, bounds.bottom,
                item.ocrConfidence)
        }
        val chosen = selectedIndices(withBounds.map { it.second })
            .map { withBounds[it].first }.toSet()
        return lines.filterIndexed { index, item ->
            item.boundingBox == null || index in chosen
        }
    }
}
