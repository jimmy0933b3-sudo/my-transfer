package com.example.cameratranslator.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLineQualityTest {
    @Test fun aCompleteHomeworkSentenceIsAccepted() {
        assertTrue(OcrLineQuality.acceptEnglish("We can see a gray horse with one foal."))
        assertTrue(OcrLineQuality.acceptEnglish("We can see a brown cow with two calves."))
    }

    @Test fun headingsRemainRecognizable() {
        assertTrue(OcrLineQuality.acceptEnglish("PM Library 2-11"))
        assertTrue(OcrLineQuality.acceptEnglish("EXPANDING FLOSS"))
    }

    @Test fun shortBilingualPackagingLabelsAreAccepted() {
        // Real regression: "BENI BEAR" has only 8 letters, and v1.5.2
        // dropped it before any cloud or offline translation was attempted.
        assertTrue(OcrLineQuality.acceptEnglish("BENI BEAR"))
        assertTrue(OcrLineQuality.acceptEnglish("Beni Bear"))
        assertTrue(OcrLineQuality.acceptEnglish("Oral B"))
        assertTrue(OcrLineQuality.acceptEnglish("PM Library 2-11"))
        assertTrue(OcrLineQuality.acceptEnglish("BENI BEAR 300"))
    }

    @Test fun chineseAndBilingualPackagingIsRecognized() {
        assertTrue(OcrLineQuality.acceptChinese("邦尼熊"))
        assertTrue(OcrLineQuality.acceptChinese("抽取式面紙300抽"))
        assertTrue(OcrLineQuality.acceptChinese("邦尼熊 BENI BEAR"))
        assertFalse(OcrLineQuality.acceptChinese("熊 BENI BEAR"))
        assertFalse(OcrLineQuality.acceptChinese("BENI BEAR"))
    }

    @Test fun partialFrameGarbageIsRejected() {
        assertFalse(OcrLineQuality.acceptEnglish("can"))
        assertFalse(OcrLineQuality.acceptEnglish("PM"))
        assertFalse(OcrLineQuality.acceptEnglish("We gray"))
        assertFalse(OcrLineQuality.acceptEnglish("three"))
        assertFalse(OcrLineQuality.acceptEnglish("lambs.an"))
    }
}
