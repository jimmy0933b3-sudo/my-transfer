package com.example.cameratranslator.core

/**
 * Display the real provider that returned each translation, not just the
 * user's selected mode. One photo can mix Azure, offline and original text.
 */
internal object PhotoEngineSummary {
    fun describe(providers: List<String>): String {
        if (providers.isEmpty()) return "尚未完成翻譯"
        var azure = 0
        var mlkit = 0
        var glossary = 0
        var unchanged = 0
        var missing = 0
        for (provider in providers) {
            when {
                provider.startsWith("Azure 雲端") -> azure++
                provider.startsWith("ML Kit 離線") -> mlkit++
                provider.startsWith("詞彙校正") || provider.startsWith("手動校正") -> glossary++
                provider.startsWith("品牌名稱保留") || provider.startsWith("相同語言") -> unchanged++
                else -> missing++
            }
        }
        val labels = buildList {
            if (azure > 0) add("Azure 雲端 " + azure + " 段")
            if (mlkit > 0) add("ML Kit 離線 " + mlkit + " 段")
            if (glossary > 0) add("本機校正 " + glossary + " 段")
            if (unchanged > 0) add("原文保留 " + unchanged + " 段")
            if (missing > 0) add("未翻譯 " + missing + " 段")
        }
        return labels.joinToString(" · ")
    }
}
