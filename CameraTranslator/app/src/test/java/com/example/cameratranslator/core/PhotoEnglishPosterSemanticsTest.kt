package com.example.cameratranslator.core

import org.junit.Assert.*
import org.junit.Test

class PhotoEnglishPosterSemanticsTest {
    private val poster = listOf(
        "ErgoFlow", "UNLOCK YOUR", "PRODUCTIVITY!",
        "Meet the new ErgoFlow Chair.", "Your back will thank you.",
        "LIMITED TIME OFFER:", "Early Bird Speciall",
        "Save S150 + FREE Shipping!", "Dynamic Lumbar Support",
        "Breathable Mesh", "Adjustable Armrests",
        "VISIT WWW.ERGOFLOW.COM/OFFER"
    )

    @Test fun evidencePreservesBrandButNotOrdinaryHeadings() {
        assertTrue(PhotoEnglishPosterSemantics.hasBrandEvidence("ErgoFlow", poster))
        assertFalse(PhotoEnglishPosterSemantics.hasBrandEvidence("Training", poster))
        assertFalse(PhotoEnglishPosterSemantics.hasBrandEvidence("Chair", poster))
        val degradedUrl = poster.dropLast(1) + "WWW.ERGOFLOW.COMIOFFER"
        assertTrue(PhotoEnglishPosterSemantics.hasBrandEvidence("ErgoFlow", degradedUrl))
    }

    @Test fun flyerSemanticsUseIndependentEvidence() {
        assertEquals("認識全新的 ErgoFlow 人體工學椅。",
            PhotoEnglishPosterSemantics.translate(
                "Meet the new ErgoFlow Chair.", "en", "zh", poster))
        assertEquals("早鳥優惠",
            PhotoEnglishPosterSemantics.translate("Early Bird Speciall", "en", "zh", poster))
        assertEquals("立即領取優惠",
            PhotoEnglishPosterSemantics.translate(
                "CLAIM YOUR OFFER NOW", "en", "zh", poster))
        assertEquals("省下 \$150，另享免運費！",
            PhotoEnglishPosterSemantics.translate(
                "Save S150 + FREE Shipping!", "en", "zh", poster))
    }

    @Test fun uncertainContextDoesNotManufactureMarketingMeaning() {
        assertNull(PhotoEnglishPosterSemantics.translate(
            "Meet the new Normal Chair.", "en", "zh", emptyList()))
        assertNull(PhotoEnglishPosterSemantics.translate(
            "Early Bird Speciall", "en", "zh", emptyList()))
        assertEquals("Save S150 + FREE Shipping!",
            PhotoEnglishPosterSemantics.normalize(
                "Save S150 + FREE Shipping!", "th", poster))
        assertEquals("Save \$150 + FREE Shipping!",
            PhotoEnglishPosterSemantics.normalize(
                "Save S150 + FREE Shipping!", "en", poster))
        assertEquals("SAVE SAND",
            PhotoEnglishPosterSemantics.normalize("SAVE SAND", "en", poster))
    }
}
