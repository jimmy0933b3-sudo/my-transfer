package com.example.cameratranslator.core

/**
 * Never paint a known-bad cloud translation onto the photograph. A failed
 * cloud quality gate may be retried with an already-installed offline model;
 * the alternate must pass exactly the same quality gate before acceptance.
 *
 * This does not download models or make additional cloud requests.
 */
internal object PhotoTranslationRecovery {
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
        translateOffline: suspend () -> String?
    ): Result {
        val rejection = translation?.let {
            PhotoTranslationQuality.rejectTranslation(original, it, source, target)
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
