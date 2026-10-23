package com.example.cameratranslator.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoOcrLanguagesTest {
    @Test fun snapshotSupportsAllRequestedSourceLanguages() {
        listOf("auto", "en", "ja", "zh", "th").forEach {
            assertTrue("Expected photo OCR support: " + it, PhotoOcrLanguages.supported(it))
        }
        assertFalse(PhotoOcrLanguages.supported("unknown"))
    }

    @Test fun thaiScriptIsNotMistakenForEnglish() {
        assertTrue(PhotoOcrLanguages.containsThai("สวัสดีครับ"))
        assertTrue(PhotoOcrLanguages.containsThai("ราคา 100 บาท"))
        assertFalse(PhotoOcrLanguages.containsThai("BENI BEAR"))
        assertFalse(PhotoOcrLanguages.containsThai("こんにちは"))
        assertFalse(PhotoOcrLanguages.containsThai("你好世界"))
    }

    @Test fun englishInsideJapanesePhotoKeepsEnglishSource() {
        org.junit.Assert.assertEquals(
            "en", PhotoOcrLanguages.resolveBlockSource("Playbook", "ja", "en")
        )
        org.junit.Assert.assertEquals(
            "en", PhotoOcrLanguages.resolveBlockSource("BENI BEAR", "zh", "en")
        )
        org.junit.Assert.assertEquals(
            "ja", PhotoOcrLanguages.resolveBlockSource("こんにちは", "ja", "ja")
        )
        org.junit.Assert.assertEquals(
            "zh", PhotoOcrLanguages.resolveBlockSource("邦尼熊", null, "zh")
        )
    }
}
