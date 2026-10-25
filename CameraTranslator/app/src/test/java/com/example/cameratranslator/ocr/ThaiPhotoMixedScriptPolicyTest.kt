package com.example.cameratranslator.ocr

import org.junit.Assert.*
import org.junit.Test

class ThaiPhotoMixedScriptPolicyTest {
    private fun english(
        text: String, left: Int, top: Int, right: Int, bottom: Int,
        confidence: Float = .98f
    ) = ThaiPhotoMixedScriptPolicy.Block(
        text, left, top, right, bottom, confidence
    )

    @Test fun mixedEnglishHeadlineSurvivesThaiModeAndFormsOneMeaningfulLine() {
        // Real v1.6.34 Paddle boxes for cosmetic ad, deliberately not x-sorted.
        val raw = listOf(
            english("Overnight", 304, 356, 520, 400),
            english("Gold", 190, 354, 294, 394),
            english("Mask", 212, 437, 350, 486),
            english("Secret", 43, 358, 187, 393),
            english("Himalaya", 87, 254, 476, 350)
        )
        val phrases = ThaiPhotoMixedScriptPolicy.groupEnglish(raw)
        assertEquals(3, phrases.size)
        assertTrue(phrases.any {
            it.text == "Secret Gold Overnight" && it.left == 43 &&
                it.right == 520
        })
        assertTrue(phrases.any { it.text == "Himalaya" })
        assertTrue(phrases.any { it.text == "Mask" })
    }

    @Test fun verifiedEnglishCannotBecomeThaiGibberishOrBrowserChrome() {
        assertFalse(ThaiPhotoMixedScriptPolicy.reliableEnglish(
            "google.com/search?", .995f))
        assertFalse(ThaiPhotoMixedScriptPolicy.reliableEnglish(
            "00:34", .99f))
        assertFalse(ThaiPhotoMixedScriptPolicy.reliableEnglish(
            "5๑6ห๕6 6๐|ส", .99f))
        assertFalse(ThaiPhotoMixedScriptPolicy.reliableEnglish(
            "Aurgs", .77f))
        assertTrue(ThaiPhotoMixedScriptPolicy.reliableEnglish(
            "Overnight", .999f))
    }

    @Test fun EnglishEvidenceSuppressesOnlySameAreaThaiHallucination() {
        val words = listOf(
            english("Secret", 43, 358, 187, 393),
            english("Gold", 190, 354, 294, 394),
            english("Overnight", 304, 356, 520, 400)
        )
        val hallucinatedThai = english(
            "5๑6ห๕6 6๐|ส องลหทเชทย", 45, 358, 518, 401
        )
        assertTrue(ThaiPhotoMixedScriptPolicy.isLatinHallucination(
            hallucinatedThai, words))
        assertFalse(ThaiPhotoMixedScriptPolicy.isLatinHallucination(
            english("ความสุข", 268, 616, 410, 647), words))
        assertFalse(ThaiPhotoMixedScriptPolicy.isLatinHallucination(
            english("ไทย", 534, 354, 620, 400),
            listOf(english("Gold", 190, 354, 294, 394))
        ))
    }
}
