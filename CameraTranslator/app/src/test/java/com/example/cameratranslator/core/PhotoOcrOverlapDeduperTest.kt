package com.example.cameratranslator.core

import org.junit.Assert.*
import org.junit.Test

class PhotoOcrOverlapDeduperTest {
    private fun block(text: String, x: Int = 0, y: Int = 0, confidence: Float = .5f) =
        PhotoOcrOverlapDeduper.Block(
            text = text, source = "ja", left = x, top = y,
            right = x + 240, bottom = y + 60, confidence = confidence
        )

    @Test fun identicalOverlappingBlocksAreDeduplicated() {
        val lines = listOf(
            block("チェックを入れよう", confidence = .3f),
            block("チェックを入れよう！", x = 5, y = 5, confidence = .9f)
        )
        assertEquals(listOf(1), PhotoOcrOverlapDeduper.selectedIndices(lines))
    }

    @Test fun distinctWordsOrLocationsAreNeverRemoved() {
        val lines = listOf(
            block("チェックを入れよう"),
            block("集めたら", x = 5, y = 5),
            block("チェックを入れよう", x = 300)
        )
        assertEquals(listOf(0, 1, 2),
            PhotoOcrOverlapDeduper.selectedIndices(lines))
    }
}
