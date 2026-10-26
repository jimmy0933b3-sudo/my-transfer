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
    @Test fun differentReadingsOnSameThaiPosterLineAreNotTwoTranslations() {
        // Real v1.6.31 diagnostic rectangles; AUTO/SPARSE disagreed on
        // characters despite recognizing the same physical red headline.
        val broad = line("ประเทศไทย.", 123, 557, 573, 103, .8371057f)
        val compact = line("ประเทษไทย.", 123, 557, 466, 79, .900147f)
        val happiness = line("ความสุข", 199, 747, 318, 73, .92057574f)
        val result = ThaiPhotoLinePolicy.merge(listOf(broad, happiness), listOf(compact))
        assertEquals(2, result.size)
        assertEquals(compact, result[0])
        assertEquals(happiness, result[1])
    }

    @Test fun geometricMatchingCannotEraseDifferentBaselinesOrSideBySideText() {
        val first = line("เทศกาลสงกรานต์", 123, 557, 470, 80, .91f)
        val second = line("ความสุขวันนี้", 125, 638, 460, 74, .93f)
        val rightColumn = line("เมษายน", 505, 557, 175, 80, .92f)
        val result = ThaiPhotoLinePolicy.merge(listOf(first, second), listOf(rightColumn))
        assertEquals(3, result.size)
    }

    @Test fun recoverOnlyGapAndShortTailNotWholePoster() {
        val lines = listOf(
            line("ประเทศไทย", 123, 557, 466, 79, .90f),
            line("ความสุข", 199, 747, 318, 73, .92f)
        )
        val regions = ThaiPhotoLinePolicy.recoveryRegions(lines, 720, 1544)
        assertEquals(2, regions.size)
        assertEquals(ThaiPhotoLinePolicy.RecoveryRegion(0, 640, 720, 743), regions[0])
        assertEquals(ThaiPhotoLinePolicy.RecoveryRegion(0, 826, 720, 966), regions[1])
        assertTrue(ThaiPhotoLinePolicy.recoveryRegions(emptyList(), 720, 1544).isEmpty())
    }

    @Test fun convertsAnyValidThaiDateRangeWithoutCloudNumericDrift() {
        assertEquals("4月13日至15日",
            ThaiPhotoLinePolicy.translateCalendarLabel("13-15 เมษายน"))
        assertEquals("4月13日至15日",
            ThaiPhotoLinePolicy.translateCalendarLabel("๑๓–๑๕ เมษายน"))
        assertEquals("12月30日至31日",
            ThaiPhotoLinePolicy.translateCalendarLabel("30—31 ธันวาคม"))
        assertEquals(null,
            ThaiPhotoLinePolicy.translateCalendarLabel("13-15 ความสุข"))
        assertEquals(null,
            ThaiPhotoLinePolicy.translateCalendarLabel("13-32 เมษายน"))
        assertEquals(null,
            ThaiPhotoLinePolicy.translateCalendarLabel("16-15 เมษายน"))
    }

    @Test fun rejectsMultiplePosterRowsPackedIntoOneOcrLine() {
        val threeRows = line("ประเทศไทยเทศกาลสงกรานต์ความสุข",
            14, 557, 696, 263)
        val validTitle = line("ประเทศไทย", 123, 557, 466, 79)
        assertFalse(ThaiPhotoLinePolicy.plausibleLineGeometry(threeRows, 720))
        assertTrue(ThaiPhotoLinePolicy.plausibleLineGeometry(validTitle, 720))
        assertTrue(ThaiPhotoLinePolicy.plausibleLineGeometry(
            line("13-15 เมษายน", 261, 878, 201, 20), 720))
    }

    @Test fun numericDateHintIsJoinedOnlyWithGenuineNearbyThaiMonth() {
        val month = line("เมษายน", 370, 865, 135, 38, .91f)
        val number = line("13–15", 265, 862, 96, 44, .92f)
        val other = line("ประเทศไทย", 123, 557, 466, 79, .90f)
        val combined = ThaiPhotoLinePolicy.attachLatinDateRanges(
            listOf(other, month), listOf(number)
        )
        assertEquals(2, combined.size)
        assertEquals("13–15 เมษายน", combined[1].text)
        assertTrue(ThaiPhotoLinePolicy.isCalendarLabel(combined[1].text))
        assertEquals(listOf(other), ThaiPhotoLinePolicy.attachLatinDateRanges(
            listOf(other), listOf(number)))
    }


    @Test fun songkranPosterPrefersReadableThaiOverHighConfidenceGibberish() {
        // Real 22:28 Tesseract AUTO vs SPARSE evidence, same printed line.
        val readable = line("ประเทศไทย .", 123, 557, 573, 103, .8371057f)
        val gibberish = line("ๆ|]ชะเทศไทย", 123, 557, 466, 79, .900147f)
        assertFalse(ThaiPhotoLinePolicy.plausible(gibberish.text))
        assertEquals(listOf(readable),
            ThaiPhotoLinePolicy.merge(listOf(readable), listOf(gibberish)))
    }

    @Test fun rejectHighCombiningMarkDensityInFakePosterLine() {
        val fake = "ไฝฮไัึื3ั1นจน1นพนแพพนพแพพไไพพไไทไไไ็ืี็ัึัึัึัึััึึืึ3จ3"
        assertFalse(ThaiPhotoLinePolicy.plausible(fake))
        assertTrue(ThaiPhotoLinePolicy.plausible("เทศกาลสงกรชานตํ"))
        assertTrue(ThaiPhotoLinePolicy.plausible("ความสุข"))
    }

    @Test fun thaiPaddleOverridesOnlySameVisualLineAndKeepsSeparatePosterRows() {
        val tesseract = listOf(
            line("ประเทศไทย .", 123, 557, 573, 103, .83f),
            line("ความสุข", 199, 747, 318, 73, .92f)
        )
        val paddle = listOf(
            line("ประเทศไทย", 124, 557, 570, 100, .95f),
            line("เทศกาลสงกรานต์", 16, 655, 684, 65, .90f)
        )
        val fused = ThaiPhotoLinePolicy.combineIndependent(tesseract, paddle)
        assertEquals(3, fused.size)
        assertEquals("ประเทศไทย", fused.first().text)
        assertTrue(fused.any { it.text == "เทศกาลสงกรานต์" })
        assertTrue(fused.any { it.text == "ความสุข" })
    }

    @Test fun damagedDateRepairRequiresBothVisibleDigitsAndRecognizedMonth() {
        assertEquals("13-15 เมษายน",
            ThaiPhotoLinePolicy.normalizeCalendarLabel("13-1ไ5 เมษายน"))
        assertEquals("4月13日至15日",
            ThaiPhotoLinePolicy.translateCalendarLabel("13-1ไ5 เมษายน"))
        assertEquals("13-1ไ5 สิ่งอื่น",
            ThaiPhotoLinePolicy.normalizeCalendarLabel("13-1ไ5 สิ่งอื่น"))
        assertEquals("13-1ไ เมษายน",
            ThaiPhotoLinePolicy.normalizeCalendarLabel("13-1ไ เมษายน"))
    }

    @Test fun paddedOcrColumnNoiseIsNotATranslatableThaiSentence() {
        assertFalse(ThaiPhotoLinePolicy.plausible(
            "ั                            รหมหนกระชับ"))
        assertFalse(ThaiPhotoLinePolicy.plausible(
            "ดูจาวลง           5      ล"))
        assertTrue(ThaiPhotoLinePolicy.plausible("รูขุมขนกระชับ"))
        assertTrue(ThaiPhotoLinePolicy.plausible("ริ้วรอยเล็ก"))
    }

    @Test fun strongerPaddleRepairsSamePhysicalThaiLineDespiteDifferentStrings() {
        // Android 16 v1.6.35 real photo: "毛孔緊緻" wrongly painted as "薩曼震撼".
        // OCR engines cover exactly the same word but use different padding.
        val tess = line("ธฐหมหนกระชบ", 337, 859, 160, 17, .76346666f)
        val paddle = line("รูVุมVนกระชับ", 332, 848, 168, 37, .8218923f)
        val merged = ThaiPhotoLinePolicy.combineIndependent(listOf(tess), listOf(paddle))
        assertEquals(1, merged.size)
        assertEquals(paddle, merged.single())
    }

    @Test fun similarThaiRowsMustNotReplaceEachOtherByFootprint() {
        val first = line("รูขุมขนกระชับ", 337, 859, 160, 25, .78f)
        val nearby = line("ริ้วรอยเล็ก", 332, 887, 168, 34, .99f)
        val merged = ThaiPhotoLinePolicy.combineIndependent(listOf(first), listOf(nearby))
        assertEquals(2, merged.size)
        assertTrue(merged.contains(first))
        assertTrue(merged.contains(nearby))
    }

    @Test fun narrowerDifferentColumnMustNotOverrideWholeThaiLine() {
        val long = line("เผยผิวสว่าง", 100, 860, 270, 32, .77f)
        val short = line("กระจ่างใส", 240, 860, 130, 34, .99f)
        val merged = ThaiPhotoLinePolicy.combineIndependent(listOf(long), listOf(short))
        // A partly overlapping Paddle box is not proof of a distinct
        // printed caption: retain Tesseract, do not overwrite or duplicate.
        assertEquals(1, merged.size)
        assertEquals(long, merged.single())
    }

    @Test fun lowerConfidencePaddleCannotReplaceConflictingTrustedThai() {
        val trusted = line("รูขุมขนกระชับ", 337, 859, 160, 17, .96f)
        val wrong = line("ธฐหมหนกระชบ", 332, 848, 168, 37, .84f)
        assertEquals(listOf(trusted),
            ThaiPhotoLinePolicy.combineIndependent(listOf(trusted), listOf(wrong)))
    }

    @Test fun realNewYearPosterRemovesNestedHeadlineFragmentsNotSeparateLines() {
        val title = line("สวัสดีปีให้ม่", 61, 406, 567, 162, .9475662f)
        val inner = line("สุด1?  ปีใ อ", 210, 419, 260, 140, .78793305f)
        val tail = line("นผู! อัง", 487, 494, 112, 53, .7397742f)
        val caption = line("หมืนคนใส่โคมไฟบนท้องฟ้า",
            198, 666, 296, 25, .9545118f)
        assertEquals(listOf(title, caption),
            ThaiPhotoLinePolicy.suppressNestedThaiFragments(
                listOf(title, inner, tail, caption)))
    }

    @Test fun keepLegitimateNeighboringOrEquallyReliableOverlappingThaiCaptions() {
        val first = line("สวัสดีปีใหม่", 61, 406, 567, 162, .94f)
        val nearby = line("ประเทศไทย", 210, 570, 260, 40, .70f)
        val strongInner = line("ความสุข", 210, 419, 260, 140, .91f)
        assertEquals(3, ThaiPhotoLinePolicy.suppressNestedThaiFragments(
            listOf(first, nearby, strongInner)).size)
    }

    @Test fun englishDateFromNewYearPosterSurvivesMalformedThaiDigitGuess() {
        assertEquals("2019年11月22日至23日，下午3時至8時30分",
            ThaiPhotoLinePolicy.translateEnglishEventDate(
                "22-23 NOV 2019/ 3: PM - 8:30 PM"))
        assertEquals("2019年11月22日至23日，下午3時至8時30分",
            ThaiPhotoLinePolicy.translateEnglishEventDate(
                "22-23 NOV 2019 / 3 PM - 8:30 PM"))
        assertEquals("2026年4月13日至15日，上午9時15分至下午5時",
            ThaiPhotoLinePolicy.translateEnglishEventDate(
                "13-15 APR 2026 / 9:15 AM - 5 PM"))
        assertNull(ThaiPhotoLinePolicy.translateEnglishEventDate(
            "22-23 FOO 2019/ 3: PM - 8:30 PM"))
        assertNull(ThaiPhotoLinePolicy.translateEnglishEventDate(
            "22-33 NOV 2019/ 3: PM - 8:30 PM"))
        assertNull(ThaiPhotoLinePolicy.translateEnglishEventDate(
            "22-23 NOV 2019/ 3 PM"))
    }
}
