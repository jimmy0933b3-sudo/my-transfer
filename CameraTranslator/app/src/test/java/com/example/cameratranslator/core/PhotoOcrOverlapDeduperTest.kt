package com.example.cameratranslator.core

import android.graphics.Rect
import com.example.cameratranslator.core.models.TextElement
import org.junit.Assert.*
import org.junit.Test

class PhotoOcrOverlapDeduperTest {
    private fun block(text: String, x: Int = 0, y: Int = 0, conf: Float = .5f) =
        TextElement(id = "$x:$y:$text", originalText = text,
            sourceLanguage = "ja", boundingBox = Rect(x, y, x + 240, y + 60),
            ocrConfidence = conf)

    @Test fun identicalOverlappingBlocksAreDeduplicated() {
        val result = PhotoOcrOverlapDeduper.deduplicate(listOf(
            block("チェックを入れよう", conf = .3f),
            block("チェックを入れよう！", x = 5, y = 5, conf = .9f)
        ))
        assertEquals(1, result.size)
        assertEquals(.9f, result.single().ocrConfidence!!, .001f)
    }

    @Test fun distinctWordsOrLocationsAreNeverRemoved() {
        val result = PhotoOcrOverlapDeduper.deduplicate(listOf(
            block("チェックを入れよう"),
            block("集めたら", x = 5, y = 5),
            block("チェックを入れよう", x = 300)
        ))
        assertEquals(3, result.size)
    }
}
