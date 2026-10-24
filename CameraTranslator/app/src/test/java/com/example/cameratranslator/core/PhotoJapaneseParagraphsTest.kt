package com.example.cameratranslator.core

import android.graphics.Rect
import com.example.cameratranslator.core.models.TextElement
import org.junit.Assert.*
import org.junit.Test

class PhotoJapaneseParagraphsTest {
    private fun line(text: String, x: Int, y: Int, review: Boolean = false) =
        TextElement(id = "line:$y", originalText = text,
            boundingBox = Rect(x, y, x + 240, y + 36), sourceLanguage = "ja",
            imageWidth = 800, imageHeight = 1200, ocrConfidence = .85f,
            ocrReviewNeeded = review)

    @Test fun mergeAdjacentContinuationForWholeSentenceTranslation() {
        val lines = listOf(
            line("集めたら", 20, 20),
            line("チェックを入れよう！", 20, 61)
        )
        val merged = PhotoJapaneseParagraphs.combine(lines)
        assertEquals(1, merged.size)
        assertTrue(merged[0].originalText.contains("集めたら"))
        assertTrue(merged[0].originalText.contains("チェックを入れよう"))
        assertEquals(Rect(20, 20, 260, 97), merged[0].boundingBox)
    }

    @Test fun differentColumnsRemainSeparate() {
        val merged = PhotoJapaneseParagraphs.combine(listOf(
            line("集めたら", 20, 20),
            line("チェックを入れよう", 370, 61)
        ))
        assertEquals(2, merged.size)
    }

    @Test fun suspiciousLineMustNotBeAbsorbedIntoTrustedText() {
        val merged = PhotoJapaneseParagraphs.combine(listOf(
            line("集めたら", 20, 20),
            line("よくわからない文字", 20, 61, review = true)
        ))
        assertEquals(2, merged.size)
        assertEquals(1, merged.count { it.ocrReviewNeeded })
    }

    @Test fun existingSentenceBoundaryPreventsMerge() {
        val merged = PhotoJapaneseParagraphs.combine(listOf(
            line("集めよう！", 20, 20), line("チェックを入れよう", 20, 61)
        ))
        assertEquals(2, merged.size)
    }
}
