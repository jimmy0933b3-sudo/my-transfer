package com.example.cameratranslator.core

import org.junit.Assert.*
import org.junit.Test

class PhotoTranslationGlossaryTest {
    @Test fun educationalBookTitleIsNotTransliterated() {
        assertEquals("遊戲手冊", PhotoTranslationGlossary.builtin("Playbook", "en", "zh"))
        assertEquals("遊戲手冊", PhotoTranslationGlossary.builtin("PLAYBOOK", "en", "zh"))
        assertEquals("遊戲手冊", PhotoTranslationGlossary.builtin("  Playbook  ", "ja", "zh"))
    }

    @Test fun glossaryDoesNotReplacePartsOfSentences() {
        assertNull(PhotoTranslationGlossary.builtin("Read the playbook carefully", "en", "zh"))
        assertNull(PhotoTranslationGlossary.builtin("Playbook", "en", "ja"))
        assertNull(PhotoTranslationGlossary.builtin("Playbook", "en", "en"))
        assertNull(PhotoTranslationGlossary.builtin("Playbooks", "en", "zh"))
    }

    @Test fun JapaneseToySheetTitlesHaveAccurateChineseNames() {
        assertEquals("收藏清單",
            PhotoTranslationGlossary.builtin("コレクションシート", "ja", "zh"))
        assertEquals("禮盒限定配色小車",
            PhotoTranslationGlossary.builtin("ギフトパック限定カラービークル！", "ja", "zh"))
        assertEquals("合金小車",
            PhotoTranslationGlossary.builtin("ダイキャストビークル", "ja", "zh"))
        assertNull(PhotoTranslationGlossary.builtin(
            "ギフトパック限定カラービークルは特別です", "ja", "zh"))
    }

    @Test fun userCorrectionKeyIsStableAcrossCase() {
        assertEquals(
            PhotoTranslationGlossary.key("Playbook", "en", "zh"),
            PhotoTranslationGlossary.key("  PLAYBOOK  ", "en", "zh")
        )
        assertNotEquals(
            PhotoTranslationGlossary.key("Playbook", "en", "zh"),
            PhotoTranslationGlossary.key("Playbook", "en", "ja")
        )
        assertFalse(PhotoTranslationGlossary.isValidCorrection("Playbook", ""))
    }

    private val collectionSheet = listOf(
        "パウバトロール", "コレクションシート", "ダイキャストピークルは",
        "マージャルコアイヤートラック"
    )

    @Test fun pawPatrolCollectionSheetOcrTyposAreCorrectedBeforeCloud() {
        val expected = mapOf(
            "パウバトロール" to "汪汪隊立大功",
            "ピークル" to "小車",
            "ダイキャストピークルは" to "合金小車",
            "全部C10種類！" to "共有 10 種！",
            "集めたらロにチエツクを入れよう！" to "收集到後，請在方框打勾！",
            "チエイスボリスカー" to "阿奇警車",
            "マージャルコアイヤートラック" to "毛毛消防車",
            "スカイコライングヘリ" to "天天直升機",
            "口ラブルバワーブルドーザー" to "小礫推土機",
            "ロッキーコリーンクルーザー" to "灰灰回收車",
            "スーマ ホバ-クSコト" to "路馬氣墊船",
            "ケントダッシニバギー" to "萊德越野車",
            "エベレストスノーキャント" to "珠珠雪地車",
            "ギコトパック限定カラービークル" to "禮盒限定配色小車",
            "マージャル コアイヤートラック メタリックver." to "毛毛消防車金屬色版",
            "ラブル パワーブルドーザーメタリックver" to "小礫推土機金屬色版"
        )
        expected.forEach { (original, translated) ->
            assertEquals(original, translated,
                PhotoTranslationGlossary.builtin(original, "ja", "zh", collectionSheet))
        }
    }

    @Test fun uncertainJapaneseOcrFragmentAndEnglishArtifactStayUntouched() {
        assertEquals("いさい",
            PhotoTranslationGlossary.builtin("いさい", "ja", "zh", collectionSheet))
        assertEquals("Bh TW",
            PhotoTranslationGlossary.builtin("Bh TW", "en", "zh", collectionSheet))
    }

    @Test fun collectionCorrectionsDoNotLeakIntoOtherPhotosOrLanguages() {
        val unrelated = listOf("自家製ピークル", "コレクションシート")
        assertNull(PhotoTranslationGlossary.builtin("ピークル", "ja", "zh"))
        assertNull(PhotoTranslationGlossary.builtin("ピークル", "ja", "zh", unrelated))
        assertNull(PhotoTranslationGlossary.builtin("ピークル", "ja", "en", collectionSheet))
        assertNull(PhotoTranslationGlossary.builtin("Bh TW", "en", "zh"))
        assertNull(PhotoTranslationGlossary.builtin(
            "小車", "zh", "zh", collectionSheet))
        assertNull(PhotoTranslationGlossary.builtin(
            "スカイコライングヘリと遊ぼう", "ja", "zh", collectionSheet))
    }

    @Test fun naturalJapanesePawPatrolNamesAreAlsoHandled() {
        assertEquals("汪汪隊立大功",
            PhotoTranslationGlossary.builtin("パウ・パトロール", "ja", "zh"))
        assertEquals("路馬氣墊船",
            PhotoTranslationGlossary.builtin(
                "ズーマ ホバークラフト", "ja", "zh", collectionSheet))
        assertEquals("萊德越野車",
            PhotoTranslationGlossary.builtin(
                "ケントダッシュバギー", "ja", "zh", collectionSheet))
    }

    @Test fun v5PhotoContextCanBeConfirmedByIndependentMlkitTitle() {
        val paddleV5 = listOf("パウ・パトロール", "コクシンシート",
            "タイキヤストビークルは", "ギトパツ限定カラービール")
        assertFalse(PhotoTranslationGlossary.isPawCollectionSheet(paddleV5))
        val combined = paddleV5 + listOf("コレクションシート")
        assertTrue(PhotoTranslationGlossary.isPawCollectionSheet(combined))
        val expected = mapOf(
            "コクシンシート" to "收藏清單",
            "集め口にを入よう!" to "收集到後，請在方框打勾！",
            "タイキヤストビークルは" to "合金小車",
            "全部Gi0種類！" to "共有 10 種！",
            "牛三は全部" to "你能全部",
            "集められるかな子" to "收集齊全嗎？",
            "マーンルアイートラリク" to "毛毛消防車",
            "チエイスポリスカー" to "阿奇警車",
            "スカイコライングヘリ" to "天天直升機",
            "ラゴルパーゴルドーー" to "小礫推土機",
            "ロツキーリーンルーー" to "灰灰回收車",
            "ケントッシンバ" to "萊德越野車",
            "ギトパツ限定カラービール" to "禮盒限定配色小車",
            "スハシヤルなメタリック.の" to "特別金屬色版本",
            "ラブルパーブドーザーメタリツ" to "小礫推土機金屬色版",
            "ビークルだよ！" to "就是這款小車！"
        )
        expected.forEach { (original, translation) ->
            assertEquals(original, translation,
                PhotoTranslationGlossary.builtin(original, "ja", "zh", combined))
        }
    }

    @Test fun v5TyposDoNotTriggerWithoutBothIndependentPhotoSignals() {
        val brandOnly = listOf("パウ・パトロール", "コクシンシート")
        val titleOnly = listOf("コレクションシート", "ダイキャスト")
        assertFalse(PhotoTranslationGlossary.isPawCollectionSheet(brandOnly))
        assertFalse(PhotoTranslationGlossary.isPawCollectionSheet(titleOnly))
        assertNull(PhotoTranslationGlossary.builtin(
            "コクシンシート", "ja", "zh", brandOnly))
        assertNull(PhotoTranslationGlossary.builtin(
            "ギトパツ限定カラービール", "ja", "zh", titleOnly))
        assertNull(PhotoTranslationGlossary.builtin(
            "マーンルアイートラリク", "ja", "en",
            brandOnly + listOf("コレクションシート")))
    }

    @Test fun giftPackSentencesMustPreserveNegativeMeaning() {
        assertEquals("禮盒限定",
            PhotoTranslationGlossary.builtin(
                "ギフトバツクでしか", "ja", "zh", collectionSheet))
        assertEquals("無法單獨取得",
            PhotoTranslationGlossary.builtin(
                "手に入らない", "ja", "zh", collectionSheet))
        assertEquals("たしょうこと",
            PhotoTranslationGlossary.builtin(
                "たしょうこと", "ja", "zh", collectionSheet))
        assertNull(PhotoTranslationGlossary.builtin(
            "手に入らない", "ja", "zh"))
    }
}
