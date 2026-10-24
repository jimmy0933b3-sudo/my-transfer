package com.example.cameratranslator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoSpeechContentTest {
    @Test
    fun originalUsesEachRecognizedBlockLanguageWhenAutoSelected() {
        val lines = listOf(
            SnapshotTranslationLine("Hello", sourceLanguage = "en"),
            SnapshotTranslationLine("こんにちは", sourceLanguage = "ja"),
            SnapshotTranslationLine("你好", sourceLanguage = "zh")
        )
        val parts = PhotoSpeechContent.forPhoto(lines, false, "zh", "auto")
        assertEquals(listOf("Hello", "こんにちは", "你好"), parts.map { it.text })
        assertEquals(listOf("en-US", "ja-JP", "zh-TW"), parts.map { it.languageTag })
    }

    @Test
    fun translatedSpeaksOnlyAvailableTranslationsInTargetLanguage() {
        val lines = listOf(
            SnapshotTranslationLine("Hello", translated = "你好", sourceLanguage = "en"),
            SnapshotTranslationLine("Skip", translated = null, sourceLanguage = "en"),
            SnapshotTranslationLine("Bye", translated = "再見", sourceLanguage = "en")
        )
        val parts = PhotoSpeechContent.forPhoto(lines, true, "zh", "auto")
        assertEquals(listOf("你好", "再見"), parts.map { it.text })
        assertTrue(parts.all { it.languageTag == "zh-TW" })
    }

    @Test
    fun unknownDetectedLanguageUsesManualSourceButNeverGuessesAuto() {
        val lines = listOf(SnapshotTranslationLine("สวัสดี", sourceLanguage = "unknown"))
        assertEquals("th-TH", PhotoSpeechContent.forPhoto(lines, false, "zh", "th")
            .single().languageTag)
        assertTrue(PhotoSpeechContent.forPhoto(lines, false, "zh", "auto").isEmpty())
    }

    @Test
    fun longSpeechIsSplitBelowEngineLimitsWithoutLosingText() {
        val longText = "Hello ".repeat(700).trim()
        val parts = PhotoSpeechContent.forPhoto(
            listOf(SnapshotTranslationLine(longText)), false, "zh", "auto"
        )
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.text.length <= 1000 })
        assertEquals(longText, parts.joinToString(" ") { it.text })
    }

    @Test
    fun blankOriginalAndTranslationNeverStartSpeech() {
        val lines = listOf(SnapshotTranslationLine("  ", translated = "\n"))
        assertTrue(PhotoSpeechContent.forPhoto(lines, false, "zh", "auto").isEmpty())
        assertTrue(PhotoSpeechContent.forPhoto(lines, true, "zh", "auto").isEmpty())
    }
}
