package com.example.cameratranslator.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoBrandPolicyTest {
    @Test fun brandNamesArePreservedOnForeignLanguagePackaging() {
        assertTrue(PhotoBrandPolicy.shouldPreserve("TAKARA TOMY", "ja"))
        assertTrue(PhotoBrandPolicy.shouldPreserve("Takara Tomy", "ja"))
        assertTrue(PhotoBrandPolicy.shouldPreserve("BENI BEAR", "ja"))
    }

    @Test fun englishLogoOnJapaneseBoxIsStillPreserved() {
        assertTrue(PhotoBrandPolicy.shouldPreserve("TAKARA TOMY", "en", "ja"))
        assertTrue(PhotoBrandPolicy.shouldPreserve("Takara Tomy", "en", "ja"))
        assertFalse(PhotoBrandPolicy.shouldPreserve("TAKARA TOMY", "en", "en"))
        assertFalse(PhotoBrandPolicy.shouldPreserve("CREATE YOUR OWN WORLD", "en", "ja"))
    }

    @Test fun completeEnglishSlogansAreNotSilentlySkipped() {
        assertFalse(PhotoBrandPolicy.shouldPreserve("Create your own Prehistoric World!", "en"))
        assertFalse(PhotoBrandPolicy.shouldPreserve("Create your own Prehistoric World!", "ja"))
        assertFalse(PhotoBrandPolicy.shouldPreserve("Create your own Prehistoric World", "ja"))
        assertFalse(PhotoBrandPolicy.shouldPreserve("We Can See A Horse", "ja"))
        assertFalse(PhotoBrandPolicy.shouldPreserve("World!", "auto"))
    }

    @Test fun preserveStandalonePawPatrolPackagingMarks() {
        assertTrue(PhotoBrandPolicy.shouldPreserve("PAW", "en", "ja"))
        assertTrue(PhotoBrandPolicy.shouldPreserve("PATROL", "en", "ja"))
        assertTrue(PhotoBrandPolicy.shouldPreserve("TOMY", "en", "ja"))
        assertTrue(PhotoBrandPolicy.shouldPreserve("TM", "en", "ja"))
        assertTrue(PhotoBrandPolicy.shouldPreserve("ver.", "en", "ja"))
        assertFalse(PhotoBrandPolicy.shouldPreserve("HELLO WORLD", "en", "en"))
        assertFalse(PhotoBrandPolicy.shouldPreserve("Collect all 10 vehicles!", "en", "ja"))
    }

    @Test fun anEnglishToChineseRequestNeverPreservesBrandInsteadOfTranslating() {
        assertFalse(PhotoBrandPolicy.shouldPreserve("TAKARA TOMY", "en"))
    }
}
