package com.example.cameratranslator.core

import com.example.cameratranslator.ocr.PhotoOcrPostProcessor
import org.junit.Assert.*
import org.junit.Test

class PhotoJapaneseParagraphsTest {
    private fun line(text: String, x: Int, y: Int, review: Boolean = false) =
        PhotoOcrPostProcessor.Segment(
            text = text, language = if (review) "ja_review" else "ja",
            left = x, top = y, right = x + 240, bottom = y + 36,
            originalIndices = listOf(y)
        )

    @Test fun mergeAdjacentContinuationForWholeSentenceTranslation() {
        val merged = PhotoOcrPostProcessor.combine(listOf(
            line("集めたら", 20, 20), line("チェックを入れよう！", 20, 61)
        ))
        assertEquals(1, merged.size)
        assertTrue(merged[0].text.contains("集めたら"))
        assertTrue(merged[0].text.contains("チェックを入れよう"))
        assertEquals(20, merged[0].left)
        assertEquals(97, merged[0].bottom)
    }

    @Test fun differentColumnsRemainSeparate() {
        val merged = PhotoOcrPostProcessor.combine(listOf(
            line("集めたら", 20, 20),
            line("チェックを入れよう", 370, 61)
        ))
        assertEquals(2, merged.size)
    }

    @Test fun uncertainLineMustNotBeAbsorbedIntoTrustedText() {
        val merged = PhotoOcrPostProcessor.combine(listOf(
            line("集めたら", 20, 20),
            line("よくわからない文字", 20, 61, review = true)
        ))
        assertEquals(2, merged.size)
    }

    @Test fun existingSentenceBoundaryPreventsMerge() {
        val merged = PhotoOcrPostProcessor.combine(listOf(
            line("集めよう！", 20, 20), line("チェックを入れよう", 20, 61)
        ))
        assertEquals(2, merged.size)
    }
}
