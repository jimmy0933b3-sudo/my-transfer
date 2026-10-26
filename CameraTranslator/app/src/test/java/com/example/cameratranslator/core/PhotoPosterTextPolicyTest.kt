package com.example.cameratranslator.core

import org.junit.Assert.*
import org.junit.Test

class PhotoPosterTextPolicyTest {
    private fun box(l: Int, t: Int, r: Int, b: Int) =
        PhotoTextBoxLayout.Box(l, t, r, b)

    @Test fun clockGlyphConfusionIsCorrectedOnlyNearAmPm() {
        assertEquals("Start: 08 AM - 11 AM",
            PhotoPosterTextPolicy.normalizeEnglishTime(
                "Start: 08 AM - 1l AM", "en"))
        assertEquals("11 PM",
            PhotoPosterTextPolicy.normalizeEnglishTime("1I PM", "en"))
        assertEquals("1l hour",
            PhotoPosterTextPolicy.normalizeEnglishTime("1l hour", "en"))
        assertEquals("1l AM",
            PhotoPosterTextPolicy.normalizeEnglishTime("1l AM", "th"))
    }

    @Test fun overlappingGraphicPosterTitlesStayUntouched() {
        val items = listOf(
            PhotoPosterTextPolicy.Block("Leadership", "en", box(260,93,630,263)),
            PhotoPosterTextPolicy.Block("TRAINING", "en", box(105,187,667,401)),
            PhotoPosterTextPolicy.Block("WORKSHOPS", "en", box(292,346,574,414)),
            PhotoPosterTextPolicy.Block("Sunday", "en", box(282,543,471,606)),
            PhotoPosterTextPolicy.Block("Unlock Your Full Potential as", "en",
                box(137,428,628,462))
        )
        assertEquals(setOf(0,1,2),
            PhotoPosterTextPolicy.protectedOverprintIndices(items))
    }

    @Test fun placeholderAndUrlArePreservedWithoutHidingRealProse() {
        val context = listOf("Lorem ipsum dolor sit amet,",
            "consectetuer adipiscing elit,", "sed diam nonummy nibh eu-")
        for (line in context + listOf("ismod tincidunt ut", "elit,")) {
            assertTrue(PhotoPosterTextPolicy.preserveLiteral(line,"en",context))
        }
        assertTrue(PhotoPosterTextPolicy.preserveLiteral(
            "www.yourwebsite.com", "en", emptyList()))
        assertFalse(PhotoPosterTextPolicy.preserveLiteral(
            "an Influential Leader", "en", context))
        assertFalse(PhotoPosterTextPolicy.preserveLiteral(
            "consectetuer adipiscing elit", "en", emptyList()))
    }
}
