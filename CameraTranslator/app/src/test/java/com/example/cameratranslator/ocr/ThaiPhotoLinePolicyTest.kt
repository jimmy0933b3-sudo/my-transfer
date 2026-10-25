package com.example.cameratranslator.ocr

import org.junit.Assert.*
import org.junit.Test

class ThaiPhotoLinePolicyTest {
    private fun line(text: String, x: Int, y: Int, w: Int, h: Int,
        conf: Float = .84f
    ) = ThaiPhotoLinePolicy.Line(text, x, y, x + w, y + h, conf)

    @Test fun posterTitleSurvivesAndSecondSegmentationAddsDate() {
        val auto = listOf(
            line("เทศกาลสงกรานต์", 120, 550, 570, 100, .80f),
            line("ความสุข", 195, 742, 320, 75, .90f)
        )
        val sparse = listOf(
            line("เทศกาลสงกรานต์", 124, 555, 560, 95, .88f),
            line("13-15", 230, 860, 120, 54, .78f),
            line("เมษายน", 365, 862, 154, 52, .75f)
        )
        val result = ThaiPhotoLinePolicy.merge(auto, sparse)
        assertEquals(3, result.size)
        assertEquals("เทศกาลสงกรานต์", result[0].text)
        assertEquals("ความสุข", result[1].text)
        assertEquals("13-15 เมษายน", result[2].text)
        assertTrue(result[2].left == 230 && result[2].right == 519)
    }

    @Test fun nearbyUnrelatedThaiWordsAreNotJoinedToDate() {
        val result = ThaiPhotoLinePolicy.merge(emptyList(), listOf(
            line("13-15", 230, 860, 120, 54),
            line("ความสุข", 365, 862, 154, 52)
        ))
        assertEquals(2, result.size)
        assertEquals("13-15", result[0].text)
    }

    @Test fun differentLinesAndColumnsRemainSeparate() {
        val result = ThaiPhotoLinePolicy.merge(
            listOf(line("ประเทศไทย", 120, 556, 400, 96)),
            listOf(line("ประเทศไทย", 120, 756, 400, 96))
        )
        assertEquals(2, result.size)
    }

    @Test fun rejectNonsenseGlyphsButRetainThaiAndArabicDates() {
        assertFalse(ThaiPhotoLinePolicy.plausible("ไ้า๊"))
        assertFalse(ThaiPhotoLinePolicy.plausible("pngtree.com/free"))
        assertTrue(ThaiPhotoLinePolicy.plausible("ประเทศไทย"))
        assertTrue(ThaiPhotoLinePolicy.plausible("13–15 เมษายน"))
        assertTrue(ThaiPhotoLinePolicy.plausible("13-15"))
        assertTrue(ThaiPhotoLinePolicy.isCalendarLabel("13-15 เมษายน"))
        assertFalse(ThaiPhotoLinePolicy.isCalendarLabel("13-15 ความสุข"))
    }

    @Test fun webpageUrlCanVetoOnlyOverlappingThaiHallucination() {
        val thaiUrl = line("คมไทยปรตาย", 43, 83, 477, 41)
        val url = line("zh.pngtree.com/free", 30, 76, 540, 62)
        val real = line("เทศกาลสงกรานต์", 123, 557, 573, 103)
        assertTrue(ThaiPhotoLinePolicy.isBrowserUrl(url.text))
        assertTrue(ThaiPhotoLinePolicy.masksThaiLine(thaiUrl, url))
        assertFalse(ThaiPhotoLinePolicy.masksThaiLine(real, url))
        assertFalse(ThaiPhotoLinePolicy.isBrowserUrl("เทศกาลสงกรานต์"))
    }

    @Test fun independentPageModesMustMatchBeforeWeakTextIsTrusted() {
        val same = line("เทศกาลสงกรานต์", 123, 557, 570, 103, .42f)
        val other = line("วันขึ้นปีใหม่", 123, 557, 570, 103, .55f)
        assertTrue(ThaiPhotoLinePolicy.corroborated(
            same, listOf(same), listOf(same.copy(left = 126))))
        assertFalse(ThaiPhotoLinePolicy.corroborated(
            same, listOf(same), listOf(other)))
    }
}
