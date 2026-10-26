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

    @Test fun validatedEventDateSurvivesEnglishRatioAndSuppressesThaiDateGibberish() {
        val date = english("22-23 NOV 2019/ 3: PM - 8:30 PM",
            163, 783, 527, 808, .9395837f)
        assertFalse(ThaiPhotoMixedScriptPolicy.reliableEnglish(
            date.text, date.confidence))
        assertTrue(ThaiPhotoMixedScriptPolicy.reliableEnglishCandidate(date))
        assertEquals(listOf(date), ThaiPhotoMixedScriptPolicy.groupEnglish(listOf(date)))
        assertTrue(ThaiPhotoMixedScriptPolicy.isLatinHallucination(
            english("22-23 พอห 2019/ 3: 9ห - 8.30 9",
                167, 785, 524, 806, .78765756f), listOf(date)))
    }

    @Test fun rotatedStockWatermarkIsNotTranslatedOrPainted() {
        assertFalse(ThaiPhotoMixedScriptPolicy.reliableEnglishCandidate(
            english("pngtee", 542, 207, 673, 339, .8987743f)))
        assertFalse(ThaiPhotoMixedScriptPolicy.reliableEnglishCandidate(
            english("ongtree", 208, 1121, 332, 1248, .9515047f)))
        assertTrue(ThaiPhotoMixedScriptPolicy.reliableEnglishCandidate(
            english("Himalaya", 87, 254, 476, 350, .97420835f)))
        assertTrue(ThaiPhotoMixedScriptPolicy.reliableEnglishCandidate(
            english("LOGO", 15, 253, 114, 290, .9965491f)))
    }
}
