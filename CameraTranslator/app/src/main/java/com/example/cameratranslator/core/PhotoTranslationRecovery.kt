package com.example.cameratranslator.core

/**
 * Never paint a known-bad cloud translation onto the photograph. A failed
 * cloud quality gate may be retried with an already-installed offline model;
 * the alternate must pass exactly the same quality gate before acceptance.
 *
 * This does not download models or make additional cloud requests.
 */
internal object PhotoTranslationRecovery {
    // Cloud/ML Kit often return only a capitalized version of Latin source
    // (e.g. "sed diam..." -> "SED DIAM..."). That is not Chinese translation.
    private fun latinEcho(source: String, candidate: String): Boolean =
        source.filter(Char::isLetterOrDigit).lowercase(java.util.Locale.ROOT) ==
            candidate.filter(Char::isLetterOrDigit).lowercase(java.util.Locale.ROOT)

    data class Result(
        val translated: String?,
        val provider: String,
        val rejectionReason: String? = null,
        val cloudQualityFailed: Boolean = false,
        val offlineAttempted: Boolean = false,
        val recovered: Boolean = false,
        val offlineUnavailable: Boolean = false
    )

    suspend fun resolve(
        original: String,
        source: String,
        target: String,
        translation: String?,
        provider: String,
        offlineReady: Boolean,
        trustedLatinWords: Set<String> = emptySet(),
        translateOffline: suspend () -> String?
    ): Result {
        val rejection = translation?.let { candidate ->
            // A cloud/offline echo is not evidence of a successful translation.
            // Japanese kanji-only titles may legitimately stay identical in
            // Chinese; deliberately preserved trademarks and user corrections
            // are handled by their own providers and remain unchanged.
            val untranslatedEcho = source != target &&
                provider in setOf("Azure 雲端", "ML Kit 離線") &&
                (original.trim() == candidate.trim() ||
                    (source == "en" && latinEcho(original, candidate))) &&
                (source in setOf("en", "th") ||
                    (source == "ja" && candidate.any { it in '\u3040'..'\u30ff' }))
            if (untranslatedEcho) "譯文與原文相同，疑似未翻譯"
            else PhotoTranslationQuality.rejectTranslation(
                original, candidate, source, target,
                if (provider == "詞彙校正（本機）") trustedLatinWords else emptySet()
            )
        }
        if (rejection == null) {
            return Result(translation, provider)
        }
        val rejected = Result(
            translated = null,
            provider = "保留原字：" + rejection,
            rejectionReason = rejection,
            cloudQualityFailed = provider == "Azure 雲端"
        )
        // Keep manually corrected/glossary text separate from cloud recovery.
        if (provider != "Azure 雲端") return rejected
        if (!offlineReady) return rejected.copy(offlineUnavailable = true)

        val alternate = translateOffline()
        if (!alternate.isNullOrBlank() &&
            // A failed/offline untranslated echo is not a successful recovery.
            (source == target || (alternate.trim() != original.trim() &&
                !(source == "en" && latinEcho(original, alternate)))) &&
            PhotoTranslationQuality.rejectTranslation(
                original, alternate, source, target
            ) == null
        ) {
            return Result(
                translated = alternate,
                provider = "ML Kit 離線（雲端譯文品質未通過）",
                cloudQualityFailed = true,
                offlineAttempted = true,
                recovered = true
            )
        }
        // If both providers fail the gate, leave the original pixels intact.
        return rejected.copy(offlineAttempted = true)
    }
}
