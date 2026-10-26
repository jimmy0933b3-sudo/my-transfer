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

    @Test fun trustedThaiFestivalTermsUseTaiwanChineseNames() {
        assertEquals("泰國",
            PhotoTranslationGlossary.builtin("ประเทศไทย .", "th", "zh"))
        assertEquals("潑水節",
            PhotoTranslationGlossary.builtin("เทศกาลสงกรานต์", "th", "zh"))
        assertEquals("潑水節",
            PhotoTranslationGlossary.builtin("เทศกาลสงกรชานตํ", "th", "zh"))
        assertEquals("幸福",
            PhotoTranslationGlossary.builtin("ความสุข", "th", "zh"))
        assertNull(PhotoTranslationGlossary.builtin("เทศกาลอื่น", "th", "zh"))
        assertNull(PhotoTranslationGlossary.builtin(
            "ประเทศไทยอร่อยมาก", "th", "zh"))
    }


    @Test fun thaiSkincareWordsMustNotBecomeRandomLiteralChinese() {
        val cases = mapOf(
            "เผยผิวสว่าง" to "展現明亮肌膚",
            "กระจ่างใส" to "透亮",
            "ในบ้ามคืน" to "一夜之間",
            "ฝ้า กระ" to "肝斑、雀斑",
            "จุดดาวดา" to "暗斑",
            "ดูจางลง" to "逐漸淡化",
            "รูVุมVนกระชับ" to "毛孔緊緻",
            "รั้วรอยเล็ก" to "細紋",
            "ดูเรียบเนียนขึ้น" to "肌膚更平滑"
        )
        cases.forEach { (thai, expected) ->
            assertEquals(thai, expected,
                PhotoTranslationGlossary.builtin(thai, "th", "zh"))
        }
        assertNull(PhotoTranslationGlossary.builtin(
            "ดูจางลงและดีกว่าเดิม", "th", "zh"))
        assertNull(PhotoTranslationGlossary.builtin(
            "รูVุมVนกระชับ", "th", "en"))
    }

    @Test fun missingThaiToneMarkNeedsIndependentSkincareEvidence() {
        assertEquals("肝斑、雀斑", PhotoTranslationGlossary.builtin(
            "ฝา กระ", "th", "zh", listOf("จุดดาวดา", "ฝา กระ")))
        assertNull(PhotoTranslationGlossary.builtin(
            "ฝา กระ", "th", "zh", listOf("ฝา กระ")))
    }

    @Test fun englishMaskSenseNeedsPhotoContext() {
        assertEquals("面膜",
            PhotoTranslationGlossary.builtin(
                "Mask", "en", "zh", listOf("Secret Gold Overnight", "Mask")
            ))
        assertNull(PhotoTranslationGlossary.builtin(
            "Mask", "en", "zh", listOf("Mask", "Halloween party")))
    }


    @Test fun independentEnglishProductLabelsDisambiguateThaiTransliterationAndBrand() {
        val actualPhoto = listOf(
            "หิมาลายา ซีเครห โกลด์ โอเวอร์ในห์ มาส์ก",
            "Himalaya",
            "Secret Gold Overnight",
            "Mask",
            "Himalaya Secret Gold",
            "เผยผิวสว่าง"
        )
        assertEquals("喜馬拉雅 Secret Gold 晚安面膜",
            PhotoTranslationGlossary.builtin(actualPhoto[0], "th", "zh", actualPhoto))
        assertEquals("喜馬拉雅",
            PhotoTranslationGlossary.builtin("Himalaya", "en", "zh", actualPhoto))
        assertEquals("Secret Gold 晚安",
            PhotoTranslationGlossary.builtin(
                "Secret Gold Overnight", "en", "zh", actualPhoto))
        assertEquals("喜馬拉雅 Secret Gold",
            PhotoTranslationGlossary.builtin(
                "Himalaya Secret Gold", "en", "zh", actualPhoto))
        assertEquals("面膜",
            PhotoTranslationGlossary.builtin("Mask", "en", "zh", actualPhoto))
    }

    @Test fun unrelatedGeographyOrUnverifiedThaiPhoneticsNeverGetProductOverride() {
        val unsupported = listOf("Himalaya", "Secret Gold Overnight", "Mountain")
        val thai = "หิมาลายา ซีเครห โกลด์ โอเวอร์ในห์ มาส์ก"
        assertNull(PhotoTranslationGlossary.builtin("Himalaya", "en", "zh", unsupported))
        assertNull(PhotoTranslationGlossary.builtin(thai, "th", "zh", unsupported))
        assertNull(PhotoTranslationGlossary.builtin(
            "Himalaya", "en", "zh", listOf("Himalaya", "Mask")))
        assertNull(PhotoTranslationGlossary.builtin(
            "Secret Gold Overnight", "en", "zh", listOf("Mask", "Overnight")))
        assertNull(PhotoTranslationGlossary.builtin(
            "Himalaya", "en", "en",
            listOf("Himalaya", "Secret Gold Overnight", "Mask")))
    }

    @Test fun posterUsesCompleteCantoneseGreetingNotNonsenseLiteralTranslation() {
        assertEquals("幸運錦鯉，恭喜發財",
            PhotoTranslationGlossary.builtin(
                "LUCKY KOI, KUNG HEIFAT CHO1", "en", "zh"))
        assertEquals("幸運錦鯉，恭喜發財",
            PhotoTranslationGlossary.builtin(
                "LUCKY KOI, KUNG HEI FAT CHOI", "en", "zh"))
        assertNull(PhotoTranslationGlossary.builtin(
            "KUNG HEIGHT CHO1", "en", "zh"))
        assertEquals("2019年11月22日至23日，下午3時至8時30分",
            PhotoTranslationGlossary.builtin(
                "22-23 NOV 2019/ 3: PM - 8:30 PM", "en", "zh"))
    }
}
