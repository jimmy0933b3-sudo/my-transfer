package com.example.cameratranslator.core

import org.junit.Assert.*
import org.junit.Test

class PhotoTranslationQualityTest {
    @Test fun rejectRealWorldJapaneseOcrGibberish() {
        assertNotNull(PhotoTranslationQuality.rejectOcr(
            "集めたらGeetをZuch et放進嘴裡", "ja"))
        assertNotNull(PhotoTranslationQuality.rejectOcr("Huichekko 口", "ja"))
        assertNull(PhotoTranslationQuality.rejectOcr(
            "集めたら□にチェックを入れよう！", "ja"))
        assertNull(PhotoTranslationQuality.rejectOcr("コレクションシート", "ja"))
    }

    @Test fun rejectJapaneseTranslationWithInventedLatinWords() {
        val original = "集めたら□にチェックを入れよう！"
        assertNotNull(PhotoTranslationQuality.rejectTranslation(
            original,
            "收藏 Geet 如果你得了，就把Zuch et放進嘴裡！",
            "ja", "zh"
        ))
        assertNotNull(PhotoTranslationQuality.rejectTranslation(
            "コレクションシート",
            "募款表 讓我們把Huichekko放進收集的Nii！",
            "ja", "zh"
        ))
        assertNull(PhotoTranslationQuality.rejectTranslation(
            original, "收集到後，請在方框裡打勾！", "ja", "zh"))
    }

    @Test fun allowLegitimateBrandAndMultilingualText() {
        assertNull(PhotoTranslationQuality.rejectOcr("TAKARA TOMY", "en"))
        assertNull(PhotoTranslationQuality.rejectOcr("BENI BEAR", "en"))
        assertNull(PhotoTranslationQuality.rejectOcr("邦尼熊", "zh"))
        assertNull(PhotoTranslationQuality.rejectOcr("สวัสดีครับ", "th"))
        assertNull(PhotoTranslationQuality.rejectTranslation(
            "TAKARA TOMY", "TAKARA TOMY", "en", "zh"))
        assertNull(PhotoTranslationQuality.rejectTranslation(
            "สวัสดีครับ", "你好", "th", "zh"))
        assertNull(PhotoTranslationQuality.rejectTranslation(
            "We can see a horse", "我們可以看到一匹馬", "en", "zh"))
    }

    @Test fun visibleJapanesePhotoTitleUsesCorrectStableTerm() {
        assertEquals("收藏清單", PhotoTranslationGlossary.builtin(
            "コレクションシート", "ja", "zh"))
    }

    @Test fun rejectSeverelyTruncatedTranslation() {
        assertNotNull(PhotoTranslationQuality.rejectTranslation(
            "ダイキャストビークルは全部で10種類！", "寶", "ja", "zh"))
    }

    @Test fun lowConfidenceShortOcrNoiseIsNotPaintedOrSentToAzure() {
        assertNotNull(PhotoTranslationQuality.rejectOcr("Dh", "en", 0.40f))
        assertNotNull(PhotoTranslationQuality.rejectOcr("Hんてi", "ja", 0.32f))
        assertNotNull(PhotoTranslationQuality.rejectOcr("C2O8", "en", 0.32f))
        assertNull(PhotoTranslationQuality.rejectOcr("PA", "en", 0.67f))
        assertNull(PhotoTranslationQuality.rejectOcr("阿奇", "zh", 0.35f))
        assertNull(PhotoTranslationQuality.rejectOcr(
            "コレクションシート", "ja", 0.31f))
    }
}
