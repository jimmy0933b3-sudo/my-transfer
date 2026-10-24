package com.example.cameratranslator.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoOrientationRankingTest {
    private fun evidence(chars: Int, horizontal: Int, vertical: Int = 0) =
        PhotoOrientationRanking.Evidence(chars, horizontal, vertical)

    @Test fun turnsClearlySidewaysText() {
        assertEquals(90, PhotoOrientationRanking.choose(mapOf(
            0 to evidence(7, 1, 4),
            90 to evidence(58, 8),
            270 to evidence(10, 1)
        )))
    }

    @Test fun canCorrectUpsideDownPortraitPhoto() {
        assertEquals(180, PhotoOrientationRanking.choose(mapOf(
            0 to evidence(6, 0, 3), 90 to evidence(8, 1),
            180 to evidence(80, 9), 270 to evidence(8, 1)
        )))
    }

    @Test fun keepsUprightAndUncertainPhotos() {
        assertEquals(0, PhotoOrientationRanking.choose(mapOf(
            0 to evidence(60, 8), 90 to evidence(55, 6), 270 to evidence(4, 0)
        )))
        assertEquals(0, PhotoOrientationRanking.choose(mapOf(
            0 to evidence(2, 0), 90 to evidence(5, 1), 270 to evidence(1, 0)
        )))
    }
}
