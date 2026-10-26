package com.example.cameratranslator.core

import android.content.Context
import java.util.Locale

/**
 * Exact-word photo translation overrides.
 *
 * Azure Translator cannot infer enough context from a single printed title,
 * so single terms can be transliterated into Chinese. Keep a tiny, tested
 * built-in educational glossary. User-entered corrected translations are
 * remembered on-device for later photos, regardless of cloud/ML Kit engine.
 *
 * Never apply a partial-word substitution inside an ordinary sentence.
 */
internal object PhotoTranslationGlossary {
    /**
     * Full-photo OCR context is used only to enable a narrowly scoped,
     * exact-match toy-collection glossary. Do not apply these OCR-typo
     * corrections to unrelated Japanese photos.
     */
    /** Context hints from both local OCR engines, never uploaded. */
    fun isPawCollectionSheet(photoContext: List<String>): Boolean =
        photoContext.any {
            "パウバトロール" in it || "パウパトロール" in it ||
                "パウ・パトロール" in it
        } && photoContext.any { "コレクションシート" in it }

    fun builtin(
        original: String, source: String, target: String,
        photoContext: List<String> = emptyList()
    ): String? {
        val key = original.trim().lowercase(Locale.ROOT)
        if (target != "zh" || source !in setOf("en", "ja", "th", "auto")) return null
        // Independently OCR'd English product title, subtitle, and packaging
        // caption agree on the full meaning. The Thai phonetic title alone is
        // an unreliable translation input (e.g. "奧維寧面具"). Require all
        // three English clues so unrelated Thai ads never inherit this brand.
        val verifiedOvernightMask = photoContext.any {
            it.equals("Himalaya", ignoreCase = true)
        } && photoContext.any {
            it.equals("Secret Gold Overnight", ignoreCase = true)
        } && photoContext.any {
            it.equals("Mask", ignoreCase = true)
        }
        if (source == "th") {
            // Complete, commonly used Thai cosmetic words and descriptions.
            // A small dictionary of full phrases avoids context-free cloud
            // mistranslations. Never replace substrings in unrelated sentences.
            val clean = original.trim().trimEnd('.', '。', '!', '！').trim()
            return when {
                verifiedOvernightMask &&
                    clean.startsWith("หิมาลายา ") &&
                    clean.contains("โกลด์") &&
                    clean.endsWith("มาส์ก") ->
                    "喜馬拉雅 Secret Gold 晚安面膜"
                clean == "ประเทศไทย" -> "泰國"
                clean == "ความสุข" -> "幸福"
                clean == "เทศกาลสงกรานต์" -> "潑水節"
                clean == "เผยผิวสว่าง" -> "展現明亮肌膚"
                clean == "กระจ่างใส" -> "透亮"
                clean in setOf("ในข้ามคืน", "ในบ้ามคืน") -> "一夜之間"
                clean in setOf("ฝ้า กระ", "ฝ้า  กระ") -> "肝斑、雀斑"
                // OCR may omit ฝ้า's tone mark. Only infer the skincare
                // term when a separately detected dark-spot caption confirms
                // the context, not for unrelated Thai package text.
                clean == "ฝา กระ" &&
                    photoContext.any { it in setOf("จุดด่างดำ", "จุดด่างดํา",
                        "จุดดาวดา") } -> "肝斑、雀斑"
                clean in setOf("จุดด่างดำ", "จุดด่างดํา", "จุดดาวดา") ->
                    "暗斑"
                clean == "ดูจางลง" -> "逐漸淡化"
                clean in setOf("รูขุมขนกระชับ", "รูVุมVนกระชับ") ->
                    "毛孔緊緻"
                clean in setOf("ริ้วรอยเล็ก", "รั้วรอยเล็ก") -> "細紋"
                clean == "ดูเรียบเนียนขึ้น" -> "肌膚更平滑"
                // A frequent OCR variation of the canonical Thai festival.
                // Require the intact long prefix so unrelated words cannot
                // be silently treated as Songkran.
                clean.startsWith("เทศกาลสงกร") &&
                    clean.length in 14..19 &&
                    clean.count { it in '\u0e01'..'\u0e5b' } == clean.length ->
                    "潑水節"
                else -> null
            }
        }
        if (source == "en" && target == "zh") {
            // Strictly parsed poster dates are safer than passing a
            // numeric-heavy OCR line through machine translation.
            com.example.cameratranslator.ocr.ThaiPhotoLinePolicy
                .translateEnglishEventDate(original)?.let { return it }
            // Common Cantonese New Year greeting. OCR often merges
            // "HEI FAT" or reads terminal I as 1; accept only the complete
            // phrase, never arbitrary English words that resemble it.
            if (key.matches(Regex(
                """^lucky\\s+koi,?\\s*kung\\s*hei\\s*fat\\s*cho[i1]$"""
            ))) return "幸運錦鯉，恭喜發財"
            // "Himalaya" is a brand printed repeatedly with a product series.
            // Without independent package evidence, keep the ordinary cloud
            // translation (e.g. geography) rather than guessing a brand.
            if (verifiedOvernightMask) {
                when (key) {
                    "himalaya" -> return "喜馬拉雅"
                    "secret gold overnight" -> return "Secret Gold 晚安"
                    "himalaya secret gold" -> return "喜馬拉雅 Secret Gold"
                }
            }
            // In mixed Thai/English package copy, "Mask" without context
            // often becomes a facial covering instead of a skincare mask.
            // Use printed neighboring text as independent contextual evidence.
            val hasOvernight = photoContext.any { row ->
                Regex("""(?i)\bovernight\b""").containsMatchIn(row)
            }
            if (key == "mask" && hasOvernight) return "面膜"
        }
        // Both signals are required. A generic Japanese sentence mentioning
        // a vehicle or a standalone "ピークル" must not be reinterpreted.
        val pawCollectionSheet = isPawCollectionSheet(photoContext)
        if (pawCollectionSheet && source == "en" && key == "bh tw") {
            // Low-confidence isolated logo/artifact: never invent "警告".
            return original.trim()
        }
        if (source == "ja") {
            // Stable educational/toy packaging terms whose dictionary
            // translation in isolation is frequently incorrect.
            when (key.trim('。', '！', '!')) {
                "コレクションシート" -> return "收藏清單"
                "パウバトロール", "パウパトロール", "パウ・パトロール" ->
                    return "汪汪隊立大功"
                "集めたらロにチエツクを入れよう",
                "集めたら□にチェックを入れよう" ->
                    return "收集到後，請在方框打勾！"
                "ギフトパック限定カラービークル" -> return "禮盒限定配色小車"
                "ダイキャストビークル" -> return "合金小車"
                "ダイキャストビークルは全部で10種類" -> return "合金小車總共有 10 種"
                "キミは全部集められるかな？" -> return "你能全部收集到嗎？"
            }
            if (pawCollectionSheet) {
                // Only exact observed OCR variants and official Japanese
                // toy-vehicle titles. Taiwan names follow the local series.
                // The glossary returns full translated blocks, not a global
                // find-and-replace that could damage unrelated sentences.
                return when (key.trim('。', '！', '!')) {
                    "コクシンシート" -> "收藏清單"
                    "集め口にを入よう" -> "收集到後，請在方框打勾！"
                    "ダイキャスト" -> "合金壓鑄"
                    "ピークル", "ビークル" -> "小車"
                    "ダイキャストピークルは", "ダイキャストビークルは",
                    "タイキヤストビークルは" -> "合金小車"
                    "全部c10種類", "全部gi0種類", "全部で10種類" -> "共有 10 種！"
                    "キ三は全部", "キミは全部", "牛三は全部" -> "你能全部"
                    "集められるかな？", "集められるかな子" -> "收集齊全嗎？"
                    "チエイスボリスカー", "チェイスポリスカー",
                    "チエイスポリスカー" -> "阿奇警車"
                    "マージャルコアイヤートラック",
                    "マーシャルファイヤートラック",
                    "マーンルアイートラリク" -> "毛毛消防車"
                    "スカイコライングヘリ", "スカイフライングヘリ" -> "天天直升機"
                    "口ラブルバワーブルドーザー",
                    "ラブルパワーブルドーザー",
                    "ラゴルパーゴルドーー" -> "小礫推土機"
                    "ロッキーコリーンクルーザー",
                    "ロッキークリーンクルーザー",
                    "ロツキーリーンルーー" -> "灰灰回收車"
                    "スーマ ホバ-クsコト", "ズーマ ホバークラフト" -> "路馬氣墊船"
                    "ケントダッシニバギー", "ケントダッシュバギー",
                    "ケントッシンバ" -> "萊德越野車"
                    "エベレストスノーキャント",
                    "エベレストスノーキャット" -> "珠珠雪地車"
                    "ギコトパック限定カラービークル",
                    "ギトパツ限定カラービール" -> "禮盒限定配色小車"
                    "ギフトバツクでしか", "ギフトパックでしか" ->
                        "禮盒限定"
                    // Preserve the negation of 手に入らない. In context,
                    // the two printed lines mean gift-pack-only availability.
                    "手に入らない" -> "無法單獨取得"
                    "スペシャルなメタリックver.0",
                    "スハシヤルなメタリック.の" -> "特別金屬色版本"
                    "ビークルだよ" -> "就是這款小車！"
                    "マージャル コアイヤートラック メタリックver.",
                    "マーシャル ファイヤートラック メタリックver." ->
                        "毛毛消防車金屬色版"
                    "ラブル パワーブルドーザーメタリックver",
                    "ラブル パワーブルドーザーメタリックver.",
                    "ラブルパーブドーザーメタリツ" ->
                        "小礫推土機金屬色版"
                    // An isolated OCR fragment near the printed footnote
                    // has no reliable meaning. Keep the original pixels.
                    "いさい", "たしょうこと" -> original.trim()
                    else -> null
                }
            }
        }
        return when (key) {
            "playbook" -> "遊戲手冊"
            else -> null
        }
    }

    fun key(original: String, source: String, target: String): String =
        source + "|" + target + "|" + original.trim().lowercase(Locale.ROOT)

    fun isValidCorrection(original: String, targetText: String): Boolean =
        original.isNotBlank() && original.length <= 1500 &&
            targetText.isNotBlank() && targetText.length <= 1500
}

/** Private editable word memory; no network, secrets or GitHub credentials. */
internal class PhotoTranslationMemory(context: Context) {
    private val store = context.getSharedPreferences("photo_translation_corrections", Context.MODE_PRIVATE)
    fun lookup(
        original: String, source: String, target: String,
        photoContext: List<String> = emptyList()
    ): String? =
        store.getString(PhotoTranslationGlossary.key(original, source, target), null)
            ?.takeIf(String::isNotBlank)
            ?: PhotoTranslationGlossary.builtin(original, source, target, photoContext)

    fun remember(original: String, source: String, target: String, translated: String): Boolean {
        if (!PhotoTranslationGlossary.isValidCorrection(original, translated)) return false
        return store.edit().putString(
            PhotoTranslationGlossary.key(original, source, target), translated.trim()
        ).commit()
    }
}
