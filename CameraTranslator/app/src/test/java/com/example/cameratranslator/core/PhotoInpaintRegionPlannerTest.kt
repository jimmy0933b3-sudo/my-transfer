package com.example.cameratranslator.core

import org.junit.Assert.*
import org.junit.Test

class PhotoInpaintRegionPlannerTest {
    private fun b(l: Int, t: Int, r: Int, d: Int) =
        PhotoInpaintRegionPlanner.Bounds(l, t, r, d)

    @Test fun separatedWordsUseSmallIndependentRegions() {
        val result = PhotoInpaintRegionPlanner.plan(
            listOf(b(20, 20, 90, 40), b(700, 550, 810, 590)), 1000, 800)
        assertEquals(2, result.regions.size)
        assertEquals(emptySet<Int>(), result.rejectedIndices)
        assertTrue(result.regions.all { it.bounds.pixels < 10_000 })
    }

    @Test fun diagonalAdjacentWordsUseOneUnionRegion() {
        val result = PhotoInpaintRegionPlanner.plan(
            listOf(b(39, 629, 411, 744), b(56, 659, 422, 775)), 720, 1544)
        assertEquals(1, result.regions.size)
        assertEquals(setOf(0, 1), result.regions.single().indices.toSet())
        assertTrue(result.regions.single().bounds.pixels < 100_000L)
    }

    @Test fun regionsClipToImageAndOversizedAreaFailsClosed() {
        val clipped = PhotoInpaintRegionPlanner.plan(
            listOf(b(-20, -30, 80, 100)), 720, 1544)
        assertEquals(0, clipped.regions.single().bounds.left)
        assertEquals(0, clipped.regions.single().bounds.top)
        val enormous = PhotoInpaintRegionPlanner.plan(
            listOf(b(0, 0, 2950, 2950)), 3000, 3000)
        assertEquals(setOf(0), enormous.rejectedIndices)
        assertTrue(enormous.regions.isEmpty())
    }

    @Test fun invalidAndOutsideBoxesNeverBecomeRegions() {
        val result = PhotoInpaintRegionPlanner.plan(
            listOf(b(10, 10, 10, 20), b(-100, 10, -50, 30),
                b(5, 5, 30, 30)), 100, 100)
        assertEquals(setOf(0, 1), result.rejectedIndices)
        assertEquals(listOf(2), result.regions.single().indices)
    }

    @Test fun chainedOverlapsMergeTransitively() {
        val result = PhotoInpaintRegionPlanner.plan(
            listOf(b(0, 0, 20, 20), b(30, 0, 50, 20),
                b(60, 0, 80, 20)), 200, 100)
        assertEquals(1, result.regions.size)
        assertEquals(setOf(0,1,2), result.regions[0].indices.toSet())
    }
}
