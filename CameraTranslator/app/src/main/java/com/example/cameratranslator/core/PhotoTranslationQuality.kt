package com.example.cameratranslator.core

import java.text.Normalizer
import java.util.Locale

/**
 * Safety gate for in-place photo editing. OCR and translation engines can
 * return syntactically valid strings containing guessed Latin gibberish.
 * Never permanently paint these onto the original photographed text.
 *
 * This is intentionally conservative: uncertainty leaves original pixels
 * untouched, with the recognized text still available for manual correction.
 */
internal object PhotoTranslationQuality {
    private val latinWords = Regex("[A-Za-z]{3,}")
    private val kana = Regex("[\u3040-\u30ff]")
    private val han = Regex("[\u3400-\u9fff]")
    private val thai = Regex("[\u0e00-\u0e7f]")
    // Only independently printed numerals are reliable. Paddle may misread
    // the Japanese で10 as Latin debris such as Gi0 or C10. Treating the
    // trailing 0 as a verified quantity rejected the correct "10 種" output.
    // Guard both sides so regex cannot match the last digit of "Gi10".
    private val numericToken = Regex("(?<![A-Za-z0-9])\\d+(?:[.,]\\d+)*(?![A-Za-z0-9])")
    private val thousandsSeparator = Regex(",(?=\\d{3}(?:\\D|$))")
    // Surface-form checks are not proof that a translation is semantically correct.
    private val japaneseNegativeEnding =
        Regex("(?:ない|なかった|ません(?:でした)?|ず)(?=$|[\\s、。！？!?])")
    private val chineseNegativeOrRestrictive = Regex("[不無无沒没未非勿別免禁難只僅唯限缺拒失]")

    private fun numbers(text: String): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return numericToken.findAll(normalized)
            .map { it.value.replace(thousandsSeparator, "") }
            .sorted().toList()
    }


    fun rejectOcr(
        original: String, source: String, confidence: Float? = null
    ): String? {
        val value = original.trim()
        // Even a good Chinese translation is harmful when its OCR input was
        // only a tiny low-confidence artifact (e.g. "Dh" or "Hんてi").
        // Larger blocks still use the existing script and quality rules.
        if (confidence != null && confidence < 0.45f &&
            value.length <= 5 && source in setOf("ja", "en")) {
            return "OCR 短文字信心過低"
        }
        if (value.isEmpty() || value.count { it.isLetterOrDigit() } < 2) {
            return "辨識文字不足"
        }
        if (value.count { it == '\uFFFD' || it == '\u25A1' } >= 4) {
            return "OCR 包含過多無法辨識的字元"
        }
        val japanese = kana.findAll(value).count()
        val chinese = han.findAll(value).count()
        val latin = latinWords.findAll(value).toList()
        if (source == "ja") {
            if (japanese + chinese < 2) return "這段不是可確認的日文"
            // On Japanese packaging, long mixed Latin words *inside* one OCR
            // text block are frequent recognition errors. Standalone logos
            // are classified as English by PhotoOcrLanguages.
            if (latin.any { it.value.length >= 4 } && (japanese + chinese) >= 2) {
                return "日文辨識混入英文片段，可能是 OCR 錯字"
            }
        }
        if (source == "zh" && chinese == 0) return "無法確認中文文字"
        if (source == "th" && !thai.containsMatchIn(value)) return "無法確認泰文字元"
        if (source == "en" && !Regex("[A-Za-z]").containsMatchIn(value)) {
            return "無法確認英文文字"
        }
        return null
    }

    fun rejectTranslation(
        original: String,
        translated: String,
        source: String,
        target: String
    ): String? {
        val input = original.trim()
        val output = translated.trim()
        if (output.isEmpty()) return "翻譯沒有回傳內容"
        if (output.contains('\uFFFD') || output.any { it.isISOControl() && it != '\n' }) {
            return "譯文包含無效字元"
        }
        if (input.length >= 7 && output.count { it.isLetterOrDigit() } <= 1) {
            return "譯文過短，可能只翻到碎片"
        }
        if (target == "zh" && source in setOf("ja", "zh", "th") &&
            input.length >= 20 && output.count { it.isLetterOrDigit() } <= 5) {
            return "原文是長句，但譯文只有少數字，可能遺漏內容"
        }
        if (output.length > input.length * 3 + 24 && input.length >= 5) {
            return "譯文異常過長"
        }
        // Compare Arabic numerals only when both sides still use numerals.
        // Chinese number words may be legitimate, so avoid speculative rejection.
        if (source != target) {
            val before = numbers(input)
            val after = numbers(output)
            if (before.isNotEmpty() && after.isNotEmpty() && before != after) {
                return "原文與譯文的數字不一致"
            }
        }
        // Catch explicit Japanese negatives that seem to turn into Chinese
        // affirmatives. "只能／限定" preserves ～しか～ない restrictions.
        if (source == "ja" && target == "zh" &&
            japaneseNegativeEnding.containsMatchIn(input) &&
            !chineseNegativeOrRestrictive.containsMatchIn(output)) {
            return "日文否定語意可能在翻譯中消失"
        }
        if (target == "zh" && source in setOf("ja", "zh", "th")) {
            // Never paint newly invented words such as 'Huichekko', 'Zuch'
            // and 'Geet' over Japanese/Chinese/Thai source text.
            val inputWords = latinWords.findAll(input)
                .map { it.value.lowercase(Locale.ROOT) }.toSet()
            val unexplainedWords = latinWords.findAll(output)
                .map { it.value.lowercase(Locale.ROOT) }
                .filterNot { it in inputWords }.toList()
            if (unexplainedWords.isNotEmpty()) {
                return "譯文含原文沒有的英文片段：" + unexplainedWords.take(2).joinToString()
            }
            // Han script is expected for actual Chinese translations.
            if (!han.containsMatchIn(output) && output != input) {
                return "譯文缺少中文內容"
            }
        }
        return null
    }
}
