package com.example.cameratranslator.core

import org.junit.Assert.*
import org.junit.Test

class PhotoNumericEvidenceTest {
    @Test fun chineseNumeralWordsPreserveQuantityAndDate() {
        assertFalse(PhotoNumericEvidence.mismatched("全部で10種類！", "總共有十種！"))
        assertFalse(PhotoNumericEvidence.mismatched("2026年10月", "2026年十月"))
        assertFalse(PhotoNumericEvidence.mismatched("2026年10月", "二〇二六年十月"))
        assertFalse(PhotoNumericEvidence.mismatched("価格 1,200 円", "價格 1200 元"))
        assertFalse(PhotoNumericEvidence.mismatched("２０％", "20%"))
        assertFalse(PhotoNumericEvidence.mismatched("全部Gi0種類！", "共有10種！"))
        assertFalse(PhotoNumericEvidence.mismatched("全部C10種類！", "共有10種！"))
    }

    @Test fun numericallyContradictoryTranslationIsRejected() {
        assertTrue(PhotoNumericEvidence.mismatched("全部で10種類！", "共有11種！"))
        assertTrue(PhotoNumericEvidence.mismatched("全部で10種類！", "總共有九種！"))
        assertTrue(PhotoNumericEvidence.mismatched("2026年10月", "2026年九月"))
        assertTrue(PhotoNumericEvidence.mismatched("ver.1.6.29", "版本1.6.30"))
    }

    @Test fun thaiSongkranDateKeepsCorrectMonthAndDays() {
        assertFalse(PhotoNumericEvidence.mismatched(
            "13-15 เมษายน", "4月13日至15日"))
        assertFalse(PhotoNumericEvidence.mismatched(
            "13–15 เมษายน", "四月十三日至十五日"))
        assertFalse(PhotoNumericEvidence.mismatched(
            "๑๓–๑๕ เมษายน", "4月13日至15日"))
        assertTrue(PhotoNumericEvidence.mismatched(
            "๑๓–๑๕ เมษายน", "4月13日至16日"))
        assertTrue(PhotoNumericEvidence.mismatched(
            "13–15 เมษายน", "4月13日至16日"))
        assertTrue(PhotoNumericEvidence.mismatched(
            "13–15 เมษายน", "5月13日至15日"))
    }

    @Test fun englishPosterDates() {
        assertFalse(PhotoNumericEvidence.mismatched("10 January", "1月10日"))
        assertFalse(PhotoNumericEvidence.mismatched("January 10", "1月10日"))
        assertFalse(PhotoNumericEvidence.mismatched("22-23 NOV 2019", "2019年11月22日至23日"))
        assertTrue(PhotoNumericEvidence.mismatched("10 January", "2月10日"))
        assertTrue(PhotoNumericEvidence.mismatched("10 January", "1月11日"))
    }

    @Test fun printedDollarValueMustSurvive() {
        assertTrue(PhotoNumericEvidence.missingUsdAmount(
            "Save $150 + FREE Shipping!", "免運費，立即領取優惠"))
        assertTrue(PhotoNumericEvidence.missingUsdAmount(
            "Now $1,200 off", "現在只折扣 200 元"))
        assertFalse(PhotoNumericEvidence.missingUsdAmount(
            "Save $150 + FREE Shipping!", "省下 150 美元，還有免運！"))
        assertFalse(PhotoNumericEvidence.missingUsdAmount(
            "Save $150", "省下一百五十美元"))
        assertFalse(PhotoNumericEvidence.missingUsdAmount(
            "Save S150", "省下優惠"))
        assertFalse(PhotoNumericEvidence.missingUsdAmount(
            "価格 150 円", "價格不明"))
    }

    @Test fun noNumericEvidenceDoesNotEraseTranslation() {
        assertFalse(PhotoNumericEvidence.mismatched("コレクションシート", "收藏清單"))
        assertFalse(PhotoNumericEvidence.mismatched("10種類", "總共有許多種類"))
    }
}
