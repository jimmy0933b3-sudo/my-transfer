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

    @Test fun japaneseNegationCannotBecomeUnqualifiedAffirmative() {
        assertNotNull(PhotoTranslationQuality.rejectTranslation(
            "手に入らない", "能入手", "ja", "zh"))
        assertNotNull(PhotoTranslationQuality.rejectTranslation(
            "この商品は買えません", "這款商品可以購買", "ja", "zh"))
        assertNull(PhotoTranslationQuality.rejectTranslation(
            "手に入らない", "無法單獨取得", "ja", "zh"))
        assertNull(PhotoTranslationQuality.rejectTranslation(
            "ギフトパックでしか手に入らない", "只能透過禮盒取得", "ja", "zh"))
        // Negative suffix must not match the middle of an unrelated word.
        assertNull(PhotoTranslationQuality.rejectTranslation(
            "ないしょの道具", "秘密道具", "ja", "zh"))
    }

    @Test fun numericalChangesAreCaughtWithoutRejectingLegitimateFormatting() {
        assertNotNull(PhotoTranslationQuality.rejectTranslation(
            "全部で10種類", "總共有 11 種", "ja", "zh"))
        assertNotNull(PhotoTranslationQuality.rejectTranslation(
            "ver.1.6.27", "版本 1.6.28", "en", "zh"))
        assertNull(PhotoTranslationQuality.rejectTranslation(
            "全部で10種類", "共有 10 種", "ja", "zh"))
        assertNull(PhotoTranslationQuality.rejectTranslation(
            "2026年10月", "2026 年 10 月", "ja", "zh"))
        assertNull(PhotoTranslationQuality.rejectTranslation(
            "価格 1,200 円", "價格 1200 元", "ja", "zh"))
        assertNull(PhotoTranslationQuality.rejectTranslation(
            "全部で10種類", "總共有十種", "ja", "zh"))
    }

    @Test fun ocrLatinDebrisMustNotBeMisreadAsARealQuantity() {
        // PP-OCRv5 has produced Latin-looking Japanese number fragments on
        // the collection sheet. The photographed text says ten varieties.
        assertNull(PhotoTranslationQuality.rejectTranslation(
            "全部Gi0種類！", "共有 10 種！", "ja", "zh"))
        assertNull(PhotoTranslationQuality.rejectTranslation(
            "全部C10種類！", "共有 10 種！", "ja", "zh"))
        // Preserve the original protection when a numeral is clearly legible.
        assertNotNull(PhotoTranslationQuality.rejectTranslation(
            "全部で20種類！", "共有 10 種！", "ja", "zh"))
        assertNotNull(PhotoTranslationQuality.rejectTranslation(
            "合計 10 個", "總共有 11 個", "ja", "zh"))
        assertNotNull(PhotoTranslationQuality.rejectTranslation(
            "ver.1.6.27", "版本 1.6.28", "en", "zh"))
        assertNull(PhotoTranslationQuality.rejectTranslation(
            "全部Gi10種類！", "共有 10 種！", "ja", "zh"))
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

    @Test fun localThaiProductCanUseIndependentlyReadBrandWithoutLooseningCloudGate() {
        val original = "หิมาลายา ซีเครห โกลด์ โอเวอร์ในห์ มาส์ก"
        val output = "喜馬拉雅 Secret Gold 晚安面膜"
        assertNotNull(PhotoTranslationQuality.rejectTranslation(
            original, output, "th", "zh"))
        assertNotNull(PhotoTranslationQuality.rejectTranslation(
            original, output, "th", "zh", setOf("secret")))
        assertNull(PhotoTranslationQuality.rejectTranslation(
            original, output, "th", "zh", setOf("secret", "gold")))
        assertNotNull(PhotoTranslationQuality.rejectTranslation(
            original, "喜馬拉雅 Secret Gold Invented 晚安面膜",
            "th", "zh", setOf("secret", "gold")))
    }

    @Test fun validatedPosterDateMayExpandEnglishMonthButNotInventWrongDate() {
        val original = "22-23 NOV 2019/ 3: PM - 8:30 PM"
        assertNull(PhotoTranslationQuality.rejectTranslation(
            original, "2019年11月22日至23日，下午3時至8時30分", "en", "zh"))
        assertNotNull(PhotoTranslationQuality.rejectTranslation(
            original, "2019年12月22日至23日，下午3時至8時30分", "en", "zh"))
        assertNotNull(PhotoTranslationQuality.rejectTranslation(
            original, "2019年11月22日至24日，下午3時至8時30分", "en", "zh"))
    }

    @Test fun mixedNumericThaiFooterNoiseIsNotPublishedAsFabricatedName() {
        assertNotNull(PhotoTranslationQuality.rejectOcr(
            "แสีถิเศวธิ218", "th", .552078f))
        assertNull(PhotoTranslationQuality.rejectOcr(
            "22-23", "th", .552078f))
        assertNull(PhotoTranslationQuality.rejectOcr(
            "สวัสดีปีใหม่", "th", .95f))
    }
}
