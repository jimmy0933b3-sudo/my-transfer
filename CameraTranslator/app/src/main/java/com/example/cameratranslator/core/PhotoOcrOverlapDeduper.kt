package com.example.cameratranslator.core

import com.example.cameratranslator.core.models.TextElement
import kotlin.math.max
import kotlin.math.min

/**
 * Remove only clear duplicate OCR rectangles for the same text. Two different
 * labels may overlap, so never dedupe based on geometry alone.
 */
internal object PhotoOcrOverlapDeduper {
    private fun key(text: String): String = text
        .filterNot { it.isWhitespace() || it in "。．、，!！?？" }
        .lowercase()

    fun deduplicate(lines: List<TextElement>): List<TextElement> {
        val kept = mutableListOf<TextElement>()
        for (line in lines) {
            val bounds = line.boundingBox
            if (bounds == null) {
                kept.add(line)
                continue
            }
            val match = kept.indexOfFirst { other ->
                val r = other.boundingBox
                if (r == null || other.sourceLanguage != line.sourceLanguage ||
                    key(other.originalText) != key(line.originalText)) false
                else {
                    val width = min(bounds.right, r.right) - max(bounds.left, r.left)
                    val height = min(bounds.bottom, r.bottom) - max(bounds.top, r.top)
                    val small = min(bounds.width().toLong() * bounds.height(),
                        r.width().toLong() * r.height())
                    width > 0 && height > 0 && small > 0 &&
                        width.toLong() * height / small.toFloat() >= 0.75f
                }
            }
            if (match < 0) kept.add(line)
            else if ((line.ocrConfidence ?: 0f) >
                (kept[match].ocrConfidence ?: 0f)) kept[match] = line
        }
        return kept
    }
}
