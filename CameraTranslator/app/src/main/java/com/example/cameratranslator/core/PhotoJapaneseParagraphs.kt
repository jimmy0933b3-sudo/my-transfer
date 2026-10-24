package com.example.cameratranslator.core

import android.graphics.Rect
import com.example.cameratranslator.core.models.TextElement
import com.example.cameratranslator.ocr.PhotoOcrPostProcessor

/**
 * Japanese grammar depends on neighboring lines. Merge only geometrically
 * adjacent lines of the same script before Azure translation; the destination
 * rectangle becomes the union of the original OCR rectangles.
 * Uncertain cross-check blocks are isolated and never merged into safe text.
 */
internal object PhotoJapaneseParagraphs {
    fun combine(elements: List<TextElement>): List<TextElement> {
        if (elements.isEmpty()) return elements
        val result = mutableListOf<TextElement>()
        // Preserve original elements that lack bounds rather than silently
        // dropping them, and allow the renderer to report invalid coordinates.
        val segments = elements.mapIndexedNotNull { index, item ->
            val box = item.boundingBox ?: return@mapIndexedNotNull null
            PhotoOcrPostProcessor.Segment(
                text = item.originalText,
                language = if (item.ocrReviewNeeded) "ja_review" else item.sourceLanguage,
                left = box.left, top = box.top,
                right = box.right, bottom = box.bottom,
                originalIndices = listOf(index)
            )
        }
        val merged = PhotoOcrPostProcessor.combine(segments)
        for (segment in merged) {
            val base = elements[segment.originalIndices.first()]
            val scores = segment.originalIndices.mapNotNull { elements[it].ocrConfidence }
            result.add(base.copy(
                id = segment.originalIndices.joinToString(prefix = "joined:", separator = "+"),
                originalText = segment.text,
                boundingBox = Rect(segment.left, segment.top, segment.right, segment.bottom),
                ocrConfidence = scores.minOrNull(),
                ocrReviewNeeded = segment.originalIndices.any { elements[it].ocrReviewNeeded }
            ))
        }
        result.addAll(elements.filter { it.boundingBox == null })
        return result.sortedWith(
            compareBy<TextElement> { it.boundingBox?.top ?: Int.MAX_VALUE }
                .thenBy { it.boundingBox?.left ?: Int.MAX_VALUE }
        )
    }
}
