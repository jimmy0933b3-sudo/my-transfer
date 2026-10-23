package com.example.cameratranslator.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotOcrClipboardTest {
    @Test fun copiesOnlyCapturedOriginalsAndPreservesOrder() {
        val lines = listOf(
            SnapshotTranslationLine(original = "Hello world", translated = "你好世界", provider = "Azure 雲端"),
            SnapshotTranslationLine(original = "ダイキャストビークル", translated = "合金車輛"),
            SnapshotTranslationLine(original = "สวัสดี", translated = "你好")
        )
        assertEquals(
            "Hello world\n\nダイキャストビークル\n\nสวัสดี",
            SnapshotOcrClipboard.all(lines)
        )
    }

    @Test fun emptySnapshotHasNothingToCopyAndParagraphsArePreserved() {
        assertEquals("", SnapshotOcrClipboard.all(emptyList()))
        assertEquals(
            "第一行\n第二行",
            SnapshotOcrClipboard.all(
                listOf(
                    SnapshotTranslationLine("  第一行\n第二行  "),
                    SnapshotTranslationLine("   ")
                )
            )
        )
    }

    @Test fun repeatedIdenticalLinesAreNotSilentlyDropped() {
        assertEquals(
            "ABC\n\nABC",
            SnapshotOcrClipboard.all(
                listOf(SnapshotTranslationLine("ABC"), SnapshotTranslationLine("ABC"))
            )
        )
    }
}
