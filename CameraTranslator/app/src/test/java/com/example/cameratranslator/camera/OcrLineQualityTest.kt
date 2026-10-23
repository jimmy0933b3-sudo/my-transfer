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

    @Test fun partialFrameGarbageIsRejected() {
        assertFalse(OcrLineQuality.acceptEnglish("can"))
        assertFalse(OcrLineQuality.acceptEnglish("PM"))
        assertFalse(OcrLineQuality.acceptEnglish("We gray"))
        assertFalse(OcrLineQuality.acceptEnglish("three"))
        assertFalse(OcrLineQuality.acceptEnglish("lambs.an"))
    }
}
