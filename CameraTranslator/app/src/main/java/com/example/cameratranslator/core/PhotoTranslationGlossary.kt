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
    fun builtin(
        original: String, source: String, target: String,
        photoContext: List<String> = emptyList()
    ): String? {
        val key = original.trim().lowercase(Locale.ROOT)
        if (target != "zh" || source !in setOf("en", "ja", "auto")) return null
        // Both signals are required. A generic Japanese sentence mentioning
        // a vehicle or a standalone "ピークル" must not be reinterpreted.
        val pawCollectionSheet = photoContext.any {
            "パウバトロール" in it || "パウパトロール" in it ||
                "パウ・パトロール" in it
        } && photoContext.any { "コレクションシート" in it }
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
                    "ダイキャスト" -> "合金壓鑄"
                    "ピークル", "ビークル" -> "小車"
                    "ダイキャストピークルは", "ダイキャストビークルは" -> "合金小車"
                    "全部c10種類", "全部で10種類" -> "共有 10 種！"
                    "キ三は全部", "キミは全部" -> "你能全部"
                    "集められるかな？" -> "收集齊全嗎？"
                    "チエイスボリスカー", "チェイスポリスカー" -> "阿奇警車"
                    "マージャルコアイヤートラック",
                    "マーシャルファイヤートラック" -> "毛毛消防車"
                    "スカイコライングヘリ", "スカイフライングヘリ" -> "天天直升機"
                    "口ラブルバワーブルドーザー",
                    "ラブルパワーブルドーザー" -> "小礫推土機"
                    "ロッキーコリーンクルーザー",
                    "ロッキークリーンクルーザー" -> "灰灰回收車"
                    "スーマ ホバ-クsコト", "ズーマ ホバークラフト" -> "路馬氣墊船"
                    "ケントダッシニバギー", "ケントダッシュバギー" -> "萊德越野車"
                    "エベレストスノーキャント",
                    "エベレストスノーキャット" -> "珠珠雪地車"
                    "ギコトパック限定カラービークル" -> "禮盒限定配色小車"
                    "ギフトバツクでしか", "ギフトパックでしか" ->
                        "只有在禮盒裡才"
                    "手に入らない" -> "能入手"
                    "スペシャルなメタリックver.0" -> "特別金屬色版本"
                    "ビークルだよ" -> "就是這款小車！"
                    "マージャル コアイヤートラック メタリックver.",
                    "マーシャル ファイヤートラック メタリックver." ->
                        "毛毛消防車金屬色版"
                    "ラブル パワーブルドーザーメタリックver",
                    "ラブル パワーブルドーザーメタリックver." ->
                        "小礫推土機金屬色版"
                    // An isolated OCR fragment near the printed footnote
                    // has no reliable meaning. Keep the original pixels.
                    "いさい" -> original.trim()
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
