package com.example.cameratranslator.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoOcrPostProcessorTest {
    private fun block(
        text: String, left: Int, top: Int, right: Int, bottom: Int,
        index: Int, language: String = "en"
    ) = PhotoOcrPostProcessor.Segment(
        text = text,
        language = language,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        originalIndices = listOf(index)
    )

    @Test fun joinsPrehistoricSloganIntoOneSentenceWithCombinedImageBounds() {
        // User's captured photograph: small first block over three
        // centered lines on a circular product label.
        val blocks = listOf(
            block("Create", 322, 371, 370, 390, 0),
            block("your 0wn\nPrehist0ric\nW0rld!", 305, 397, 389, 457, 1)
        )
        val result = PhotoOcrPostProcessor.combine(blocks)
        assertEquals(1, result.size)
        assertEquals("Create your own Prehistoric World!", result.single().text)
        assertEquals(305, result.single().left)
        assertEquals(371, result.single().top)
        assertEquals(389, result.single().right)
        assertEquals(457, result.single().bottom)
        assertEquals(listOf(0, 1), result.single().originalIndices)
    }

    @Test fun keepsUnrelatedColumnsAndDistantLabelsSeparate() {
        val result = PhotoOcrPostProcessor.combine(
            listOf(
                block("Create", 20, 20, 110, 45, 0),
                block("Dinosaurs", 250, 48, 360, 75, 1),
                block("World!", 20, 145, 112, 172, 2)
            )
        )
        assertEquals(3, result.size)
    }

    @Test fun punctuationAndNumberingStopMerging() {
        val result = PhotoOcrPostProcessor.combine(
            listOf(
                block("See the animals.", 20, 30, 280, 52, 0),
                block("1. Create your own", 25, 55, 280, 80, 1),
                block("2. Find fossils", 27, 82, 285, 105, 2)
            )
        )
        assertEquals(3, result.size)
    }

    @Test fun differentScriptsAreNotJoinedTogether() {
        val output = PhotoOcrPostProcessor.combine(listOf(
            block("TAKARA TOMY", 20, 20, 180, 42, 0),
            block("ダイキャスト", 20, 45, 188, 68, 1, "ja")
        ))
        assertEquals(2, output.size)
    }

    @Test fun fixesOZeroOnlyInLikelyAlphabeticWords() {
        assertEquals(
            "Create your own Prehistoric World!",
            PhotoOcrPostProcessor.repairLatinOZero("Create y0ur 0wn Prehist0ric W0rld!")
        )
        assertEquals(
            "300 sheets · 2026 · R2D2 · B0X · 10 items",
            PhotoOcrPostProcessor.repairLatinOZero("300 sheets · 2026 · R2D2 · B0X · 10 items")
        )
        assertFalse(PhotoOcrPostProcessor.repairLatinOZero("0wn").contains('0'))
        assertTrue(PhotoOcrPostProcessor.repairLatinOZero("0") == "0")
    }
}
