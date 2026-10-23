package com.example.cameratranslator.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThaiModelIntegrityTest {
    @Test fun thaiScriptCheckRejectsLatinNoiseAndKeepsThai() {
        assertTrue(ThaiModelIntegrity.isThaiLine("สวัสดีครับ"))
        assertTrue(ThaiModelIntegrity.isThaiLine("ราคา 100 บาท"))
        assertFalse(ThaiModelIntegrity.isThaiLine("Hello World"))
        assertFalse(ThaiModelIntegrity.isThaiLine("BENI BEAR"))
        assertFalse(ThaiModelIntegrity.isThaiLine("ก English Sample"))
    }

    @Test fun missingOrIncorrectModelMustNeverBeInstalled() {
        val missing = File("nonexistent-tha.traineddata")
        assertFalse(ThaiModelIntegrity.verified(missing))
        val temp = File.createTempFile("thai-train", ".data")
        try {
            temp.writeText("incomplete")
            assertFalse(ThaiModelIntegrity.verified(temp))
            // Even an exact-size payload is not sufficient without SHA-1.
            temp.outputStream().buffered().use { out ->
                val zeroes = ByteArray(8192)
                var remaining = ThaiModelIntegrity.BYTES
                while (remaining > 0) {
                    val count = minOf(remaining, zeroes.size.toLong()).toInt()
                    out.write(zeroes, 0, count)
                    remaining -= count
                }
            }
            assertEquals(ThaiModelIntegrity.BYTES, temp.length())
            assertFalse(ThaiModelIntegrity.verified(temp))
        } finally {
            temp.delete()
        }
    }

    @Test fun versionPinnedModelFingerprintNeverChangesSilently() {
        assertEquals(1_072_600L, ThaiModelIntegrity.BYTES)
        assertEquals("ea28de3985668cac00d261c5b91f023ea66e19cd",
            ThaiModelIntegrity.GIT_BLOB_SHA1)
    }
}
