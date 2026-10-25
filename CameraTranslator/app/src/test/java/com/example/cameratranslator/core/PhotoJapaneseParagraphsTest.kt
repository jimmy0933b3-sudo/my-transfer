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

    @Test fun actualPhotoDiagonalHeadlineBoxesMustRemainIndependent() {
        // Real 1500x2000 OCR geometry from the uploaded PAW PATROL sheet:
        // multiple yellow/red Japanese rows and small ruby annotations.
        val blocks = listOf(
            PhotoOcrPostProcessor.Segment(
                "ダイキャストビークルは", "ja", 974, 481, 1404, 611, listOf(8)),
            PhotoOcrPostProcessor.Segment(
                "全部で10種類", "ja", 1032, 540, 1350, 653, listOf(9)),
            PhotoOcrPostProcessor.Segment(
                "キミは全部集められるかな", "ja",
                1022, 635, 1370, 756, listOf(10))
        )
        val merged = PhotoOcrPostProcessor.combine(blocks)
        assertEquals("Diagonal overlapping labels are not one rectangle",
            3, merged.size)
        assertEquals(setOf(8, 9, 10),
            merged.flatMap { it.originalIndices }.toSet())
    }

    @Test fun separatedJapaneseLinesCanStillBeTranslatedAsSentence() {
        val blocks = listOf(
            PhotoOcrPostProcessor.Segment(
                "集めたら", "ja", 200, 200, 440, 250, listOf(0)),
            PhotoOcrPostProcessor.Segment(
                "チェックを入れよう", "ja", 201, 260, 445, 310, listOf(1))
        )
        val merged = PhotoOcrPostProcessor.combine(blocks)
        assertEquals(1, merged.size)
        assertEquals(listOf(0, 1), merged.single().originalIndices)
    }

    @Test fun existingSentenceBoundaryPreventsMerge() {
        val merged = PhotoOcrPostProcessor.combine(listOf(
            line("集めよう！", 20, 20), line("チェックを入れよう", 20, 61)
        ))
        assertEquals(2, merged.size)
    }
}
