package com.example.cameratranslator.core

import org.junit.Assert.*
import org.junit.Test

class PhotoTextBoxLayoutTest {
    private fun box(left: Int, top: Int, right: Int, bottom: Int) =
        PhotoTextBoxLayout.Box(left, top, right, bottom)

    @Test fun pawPatrolDiagonalHeadlineFourRowsAreAllGivenNonOverlappingSpace() {
        // Exact original 1500x2000 logged geometry from photo block 8-11.
        val originals = listOf(
            box(974, 481, 1404, 611),
            box(1032, 540, 1350, 653),
            box(1076, 596, 1311, 692),
            box(1022, 635, 1370, 756)
        )
        val partitioned = PhotoTextBoxLayout.partition(originals).requireNoNulls()
        assertEquals(4, partitioned.size)
        assertTrue(partitioned.all { it.valid })
        assertTrue("First printed row must remain the topmost", partitioned[0].top == 481)
        for (i in 0..2) {
            assertTrue("Rows must never collide: " + i,
                partitioned[i].bottom <= partitioned[i + 1].top)
            assertTrue("Do not shrink to a thin unusable sliver",
                partitioned[i].height >= 18)
        }
        assertTrue(partitioned[3].height >= 18)
    }

    @Test fun lowerGiftPackHeadlinesArePartitionedWithoutDiscardingLines() {
        // v1.6.23 v6 text boxes from the right-hand metallic-version notice.
        val original = listOf(
            box(997, 1389, 1354, 1503),
            box(998, 1453, 1245, 1549),
            box(995, 1474, 1500, 1598),
            box(995, 1550, 1272, 1650)
        )
        val planned = PhotoTextBoxLayout.partition(original).requireNoNulls()
        assertTrue(planned.all { it.valid })
        assertTrue(planned[0].bottom <= planned[1].top)
        assertTrue(planned[1].bottom <= planned[2].top)
        assertTrue(planned[2].bottom <= planned[3].top)
    }

    @Test fun sameBaselineOverlappingOcrIsNotSplit() {
        val first = box(50, 100, 370, 165)
        val second = box(60, 110, 370, 175)
        assertEquals(listOf(first, second),
            PhotoTextBoxLayout.partition(listOf(first, second)))
    }

    @Test fun tinyFuriganaCannotCutFullPrintedSentence() {
        val main = box(100, 100, 500, 200)
        val ruby = box(160, 100, 250, 119)
        assertEquals(listOf(main, ruby),
            PhotoTextBoxLayout.partition(listOf(main, ruby)))
    }

    @Test fun horizontalColumnsAndDisjointBoxesNeverChange() {
        val boxes = listOf(box(10, 20, 220, 110),
            box(200, 70, 380, 170), box(400, 100, 700, 200))
        assertEquals(boxes, PhotoTextBoxLayout.partition(boxes))
    }

    @Test fun invalidOrNullBoxesRemainUntouched() {
        val boxes = listOf(
            null, box(30, 50, 42, 60), box(45, 50, 400, 120))
        assertEquals(boxes, PhotoTextBoxLayout.partition(boxes))
    }
}
