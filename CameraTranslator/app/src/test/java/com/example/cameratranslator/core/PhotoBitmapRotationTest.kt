package com.example.cameratranslator.core

import com.example.cameratranslator.core.models.OcrPoint
import org.junit.Assert.*
import org.junit.Test

class PhotoBitmapRotationTest {
    @Test fun clockwiseRotationKeepsTextQuadInPhotoCoordinates() {
        val original = listOf(
            OcrPoint(100f, 200f), OcrPoint(400f, 145f),
            OcrPoint(410f, 170f), OcrPoint(110f, 225f)
        )
        val expected = listOf(
            OcrPoint(800f, 100f), OcrPoint(855f, 400f),
            OcrPoint(830f, 410f), OcrPoint(775f, 110f)
        )
        val rotated = PhotoBitmapRotation.clockwise(original, 1000)
        assertEquals(expected, rotated)
        assertTrue(PhotoQuadGeometry.eligible(rotated))
    }

    @Test fun fourQuarterTurnsRestoreOriginalQuad() {
        val original = listOf(
            OcrPoint(20f, 50f), OcrPoint(220f, 20f),
            OcrPoint(225f, 50f), OcrPoint(25f, 80f)
        )
        var quad = original
        var width = 300
        var height = 200
        repeat(4) {
            quad = PhotoBitmapRotation.clockwise(quad, height)
            val previousWidth = width
            width = height
            height = previousWidth
        }
        assertEquals(original, quad)
        assertEquals(300, width)
        assertEquals(200, height)
    }
}
