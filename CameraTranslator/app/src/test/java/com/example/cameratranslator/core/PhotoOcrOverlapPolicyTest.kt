package com.example.cameratranslator.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoOcrOverlapPolicyTest {
    @Test fun overlappingThaiScriptSupersedesPaddleBox() {
        assertTrue(PhotoOcrOverlapPolicy.significantOverlap(
            10, 10, 210, 70, 25, 15, 180, 60
        ))
    }

    @Test fun nearbyEnglishLabelRemains() {
        assertFalse(PhotoOcrOverlapPolicy.significantOverlap(
            10, 10, 180, 50, 10, 53, 180, 90
        ))
    }

    @Test fun smallPartialOverlapDoesNotRemoveOtherScript() {
        assertFalse(PhotoOcrOverlapPolicy.significantOverlap(
            10, 10, 110, 60, 90, 10, 190, 60
        ))
    }

    @Test fun invalidBoxesNeverIntersect() {
        assertFalse(PhotoOcrOverlapPolicy.significantOverlap(
            20, 20, 10, 10, 0, 0, 100, 100
        ))
    }
}
