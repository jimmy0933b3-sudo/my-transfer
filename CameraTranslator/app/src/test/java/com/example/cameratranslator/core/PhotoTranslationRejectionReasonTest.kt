package com.example.cameratranslator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhotoTranslationRejectionReasonTest {
    @Test
    fun mixedLatinRejectionDoesNotCopyRecognizedWordsIntoLogs() {
        val code = PhotoTranslationRejectionReason.code(
            "譯文含原文沒有的英文片段：SampleBrand",
            "translation"
        )
        assertEquals("translation_unexpected_latin", code)
        assertFalse(code.contains("SampleBrand"))
    }

    @Test
    fun correctlyCategorizesThaiAndTranslationQuality() {
        assertEquals("ocr_not_thai", PhotoTranslationRejectionReason.code(
            "無法確認泰文字元", "ocr"
        ))
        assertEquals("translation_not_chinese", PhotoTranslationRejectionReason.code(
            "譯文缺少中文內容", "translation"
        ))
        assertEquals("translation_too_short", PhotoTranslationRejectionReason.code(
            "譯文過短，可能只翻到碎片", "translation"
        ))
    }

    @Test
    fun unknownReasonsUseFixedFallbacks() {
        assertEquals("ocr_other", PhotoTranslationRejectionReason.code(
            "unknown OCR diagnostic", "ocr"
        ))
        assertEquals("translation_other", PhotoTranslationRejectionReason.code(
            "unknown translation diagnostic", "translation"
        ))
        assertEquals("other", PhotoTranslationRejectionReason.code(
            "unknown stage diagnostic", "unknown"
        ))
    }
}
