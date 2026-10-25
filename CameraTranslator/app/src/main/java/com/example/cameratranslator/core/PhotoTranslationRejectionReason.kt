package com.example.cameratranslator.core

/**
 * Fixed diagnostic labels only. Never write OCR content or translated phrases
 * into the app's diagnostic log. In particular, a quality rejection may
 * contain extracted Latin words from a photographed document.
 */
internal object PhotoTranslationRejectionReason {
    fun code(reason: String, stage: String): String = when (stage) {
        "ocr" -> when {
            reason.startsWith("辨識文字不足") -> "ocr_too_short"
            reason.startsWith("OCR 包含過多無法辨識的字元") -> "ocr_invalid_chars"
            reason.startsWith("這段不是可確認的日文") -> "ocr_not_japanese"
            reason.startsWith("日文辨識混入英文片段") -> "ocr_mixed_latin"
            reason.startsWith("無法確認中文文字") -> "ocr_not_chinese"
            reason.startsWith("無法確認泰文字元") -> "ocr_not_thai"
            reason.startsWith("無法確認英文文字") -> "ocr_not_english"
            else -> "ocr_other"
        }
        "translation" -> when {
            reason.startsWith("翻譯沒有回傳內容") -> "translation_empty"
            reason.startsWith("譯文包含無效字元") -> "translation_invalid_chars"
            reason.startsWith("譯文過短") -> "translation_too_short"
            reason.startsWith("原文是長句") -> "translation_incomplete"
            reason.startsWith("譯文異常過長") -> "translation_too_long"
            reason.startsWith("譯文含原文沒有的英文片段") -> "translation_unexpected_latin"
            reason.startsWith("譯文缺少中文內容") -> "translation_not_chinese"
            reason.startsWith("原文與譯文的數字不一致") -> "translation_numeric_mismatch"
            reason.startsWith("日文否定語意可能在翻譯中消失") -> "translation_negation_risk"
            reason.startsWith("譯文與原文相同") -> "translation_untranslated_echo"
            else -> "translation_other"
        }
        else -> "other"
    }
}
