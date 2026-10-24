package com.example.cameratranslator.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhotoTranslationRecoveryTest {
    private val longThai = "นี่คือข้อความภาษาไทยที่ยาวมากและใช้ในการทดสอบ"

    @Test
    fun rejectedCloudShortTranslationCanBeRecoveredOffline() = runBlocking {
        var calls = 0
        val result = PhotoTranslationRecovery.resolve(
            original = longThai, source = "th", target = "zh",
            translation = "好", provider = "Azure 雲端",
            offlineReady = true
        ) {
            calls++
            "這是一段完整的泰文測試內容"
        }
        assertEquals(1, calls)
        assertTrue(result.recovered)
        assertTrue(result.offlineAttempted)
        assertTrue(result.cloudQualityFailed)
        assertEquals("這是一段完整的泰文測試內容", result.translated)
        assertNull(result.rejectionReason)
    }

    @Test
    fun acceptedCloudTranslationDoesNotCallOffline() = runBlocking {
        val result = PhotoTranslationRecovery.resolve(
            original = "สวัสดีครับ", source = "th", target = "zh",
            translation = "你好", provider = "Azure 雲端",
            offlineReady = true
        ) {
            fail("Offline translation must not run for accepted cloud text")
            "不應執行"
        }
        assertEquals("你好", result.translated)
        assertFalse(result.offlineAttempted)
        assertFalse(result.cloudQualityFailed)
    }

    @Test
    fun offlineEchoDoesNotCountAsARecoveredTranslation() = runBlocking {
        val result = PhotoTranslationRecovery.resolve(
            original = longThai, source = "th", target = "zh",
            translation = "好", provider = "Azure 雲端",
            offlineReady = true
        ) { longThai }
        assertNull(result.translated)
        assertTrue(result.offlineAttempted)
        assertFalse(result.recovered)
    }

    @Test
    fun missingOfflineModelsDoNotCauseNetworkOrUnverifiedReplacement() = runBlocking {
        val result = PhotoTranslationRecovery.resolve(
            original = longThai, source = "th", target = "zh",
            translation = "好", provider = "Azure 雲端",
            offlineReady = false
        ) {
            fail("Must not download or translate without installed models")
            null
        }
        assertNull(result.translated)
        assertTrue(result.cloudQualityFailed)
        assertTrue(result.offlineUnavailable)
        assertFalse(result.offlineAttempted)
        assertNotNull(result.rejectionReason)
    }

    @Test
    fun secondBadTranslationMustNotOverwriteOriginal() = runBlocking {
        val result = PhotoTranslationRecovery.resolve(
            original = longThai, source = "th", target = "zh",
            translation = "好", provider = "Azure 雲端",
            offlineReady = true
        ) { "Hello invented English" }
        assertNull(result.translated)
        assertTrue(result.offlineAttempted)
        assertFalse(result.recovered)
        assertNotNull(result.rejectionReason)
    }

    @Test
    fun nonCloudGlossaryFailuresDoNotTriggerAutomaticReplacement() = runBlocking {
        val result = PhotoTranslationRecovery.resolve(
            original = longThai, source = "th", target = "zh",
            translation = "好", provider = "詞彙校正（本機）",
            offlineReady = true
        ) {
            fail("User-provided glossary result must not be automatically overridden")
            "正確翻譯"
        }
        assertNull(result.translated)
        assertFalse(result.cloudQualityFailed)
        assertFalse(result.offlineAttempted)
    }

    @Test
    fun absentPrimaryTranslationStaysAbsentWithoutNewCloudRequests() = runBlocking {
        val result = PhotoTranslationRecovery.resolve(
            original = longThai, source = "th", target = "zh",
            translation = null, provider = "未翻譯，請檢查 Azure／離線模型",
            offlineReady = true
        ) {
            fail("Absent cloud response already followed the existing fallback path")
            "不應執行"
        }
        assertNull(result.translated)
        assertNull(result.rejectionReason)
        assertFalse(result.offlineAttempted)
    }
}
