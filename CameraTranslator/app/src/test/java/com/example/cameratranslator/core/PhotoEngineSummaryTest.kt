package com.example.cameratranslator.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoEngineSummaryTest {
    @Test fun reportsActualAzureAndOfflineFallbackSeparately() {
        assertEquals(
            "Azure 雲端 2 段 · ML Kit 離線 1 段 · 原文保留 1 段",
            PhotoEngineSummary.describe(
                listOf("Azure 雲端", "Azure 雲端（校正後）",
                    "ML Kit 離線", "品牌名稱保留原文")
            )
        )
    }

    @Test fun noProofOfCloudMeansNoAzureLabel() {
        assertEquals("ML Kit 離線 1 段 · 未翻譯 1 段",
            PhotoEngineSummary.describe(
                listOf("ML Kit 離線（校正後）", "未翻譯，請檢查 Azure／離線模型")
            ))
        assertEquals("尚未完成翻譯", PhotoEngineSummary.describe(emptyList()))
    }

    @Test fun localGlossaryAndSameLanguageAreNotClaimedAsRemoteTranslation() {
        assertEquals("本機校正 2 段 · 原文保留 1 段",
            PhotoEngineSummary.describe(
                listOf("手動校正（下次同詞自動套用）", "詞彙校正（本機）", "相同語言原文")
            ))
    }
}
