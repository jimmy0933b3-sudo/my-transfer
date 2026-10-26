package com.example.cameratranslator.core

import com.example.cameratranslator.core.models.OcrPoint
import org.junit.Assert.*
import org.junit.Test

class PhotoQuadGeometryTest {
    private fun q(vararg values: Float): List<OcrPoint> =
        values.toList().chunked(2).map { OcrPoint(it[0], it[1]) }

    @Test fun diagonalPosterRowsAreActuallySeparateDespiteBoundingRectCollision() {
        val headline = q(39f, 708f, 403f, 629f, 411f, 665f, 47f, 744f)
        val second = q(56f, 741f, 414f, 659f, 422f, 693f, 63f, 775f)
        val caption = q(73f, 775f, 401f, 702f, 407f, 726f, 78f, 799f)
        assertTrue(PhotoQuadGeometry.eligible(headline))
        assertTrue(PhotoQuadGeometry.eligible(second))
        assertTrue(PhotoQuadGeometry.eligible(caption))
        assertTrue(PhotoQuadGeometry.angleDegrees(headline) < -5f)
        assertTrue(PhotoQuadGeometry.overlapFraction(headline, second) < .23f)
        assertTrue(PhotoQuadGeometry.overlapFraction(second, caption) < .23f)
        // Axis-aligned intersection of the first/second headline bounding
        // rectangles is much larger than the actual thin glyph-row overlap.
        val firstBox = PhotoQuadGeometry.rectangle(39,629,411,744)
        val secondBox = PhotoQuadGeometry.rectangle(56,659,422,775)
        assertTrue(PhotoQuadGeometry.overlapFraction(firstBox, secondBox) > .23f)
    }

    @Test fun genuinePolygonsAreRejectedWhenTheyOverlayOneAnother() {
        val first = q(100f, 130f, 300f, 100f, 305f, 140f, 105f, 170f)
        val other = q(104f, 130f, 300f, 104f, 304f, 144f, 108f, 170f)
        assertTrue(PhotoQuadGeometry.overlapFraction(first, other) > .75f)
    }

    @Test fun manualQuarterTurnStillUsesRotatedQuad() {
        val row = q(100f, 130f, 300f, 90f, 310f, 130f, 110f, 170f)
        val quarterTurn = PhotoBitmapRotation.clockwise(row, 800)
        assertTrue(PhotoQuadGeometry.eligible(row))
        assertTrue(PhotoQuadGeometry.angleDegrees(quarterTurn) > 75f)
        assertTrue(PhotoQuadGeometry.eligible(quarterTurn))
        val halfTurn = PhotoBitmapRotation.clockwise(quarterTurn, 800)
        assertTrue(PhotoQuadGeometry.eligible(halfTurn))
    }

    @Test fun invalidConcaveQuadCannotEraseBackground() {
        val concave = q(100f, 130f, 300f, 90f, 160f, 145f, 110f, 170f)
        assertFalse(PhotoQuadGeometry.eligible(concave))
    }

    @Test fun polygonInsideAndOutsideSamples() {
        val p = q(100f, 130f, 300f, 100f, 305f, 140f, 105f, 170f)
        assertTrue(PhotoQuadGeometry.contains(p, 198f, 133f))
        assertFalse(PhotoQuadGeometry.contains(p, 198f, 180f))
        assertFalse(PhotoQuadGeometry.eligible(
            PhotoQuadGeometry.rectangle(100,100,200,160)))
    }
}
