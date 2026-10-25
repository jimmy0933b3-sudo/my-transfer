package com.example.cameratranslator.core

import org.junit.Assert.*
import org.junit.Test

class PhotoJapaneseCrosscheckPolicyTest {
    @Test fun manualJapaneseAlwaysUsesSecondReader() {
        assertTrue(PhotoJapaneseCrosscheckPolicy.shouldCrosscheck("ja", listOf("en")))
        assertTrue(PhotoJapaneseCrosscheckPolicy.shouldCrosscheck("ja", emptyList()))
    }

    @Test fun autoJapaneseDominantPhotoUsesSecondReader() {
        assertTrue(PhotoJapaneseCrosscheckPolicy.shouldCrosscheck(
            "auto", listOf("ja", "ja", "ja", "en", "zh")))
        assertTrue(PhotoJapaneseCrosscheckPolicy.shouldCrosscheck(
            "auto", listOf("ja", "ja", "en", "en")))
    }

    @Test fun autoEnglishAndChinesePhotosRemainSinglePass() {
        assertFalse(PhotoJapaneseCrosscheckPolicy.shouldCrosscheck(
            "auto", listOf("en", "zh", "en", "ja")))
        assertFalse(PhotoJapaneseCrosscheckPolicy.shouldCrosscheck(
            "auto", listOf("ja")))
        assertFalse(PhotoJapaneseCrosscheckPolicy.shouldCrosscheck("auto", emptyList()))
    }

    @Test fun otherManualLanguagesNeverStartJapaneseCrosscheck() {
        for (language in listOf("en", "zh", "th")) {
            assertFalse(PhotoJapaneseCrosscheckPolicy.shouldCrosscheck(
                language, listOf("ja", "ja", "ja")))
        }
    }
}
