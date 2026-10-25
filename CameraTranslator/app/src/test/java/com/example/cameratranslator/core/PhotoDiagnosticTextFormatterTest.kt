package com.example.cameratranslator.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoDiagnosticTextFormatterTest {
    @Test
    fun keepsEntireOriginalAndTranslationInsteadOfTruncating() {
        val text = "日文OCR" + "長".repeat(2000) + " 中文譯文"
        assertEquals(text, PhotoDiagnosticTextFormatter.escape(text))
    }

    @Test
    fun escapesLineBreaksAndLiteralBackslashesWithoutLosingWords() {
        val value = "OCR\\raw\n第一行\r第二行\t譯文"
        assertEquals("OCR\\\\raw\\n第一行\\r第二行\\t譯文",
            PhotoDiagnosticTextFormatter.escape(value))
    }

    @Test
    fun emptyOrRejectedTranslationHasStableRepresentation() {
        assertEquals("", PhotoDiagnosticTextFormatter.escape(""))
        assertEquals("中文", PhotoDiagnosticTextFormatter.escape("中文"))
    }
}
