package com.example.cameratranslator.core

/**
 * Speech is generated only from captured-photo text, never from camera frames.
 * A photo may have blocks in several languages when OCR source is "auto".
 */
internal data class PhotoSpeechPhrase(val text: String, val languageTag: String)

internal object PhotoSpeechContent {
    private const val MAX_SPEECH_CHUNK = 1000

    fun forPhoto(
        lines: List<SnapshotTranslationLine>,
        translated: Boolean,
        targetLanguage: String,
        sourceFallback: String
    ): List<PhotoSpeechPhrase> = lines.flatMap { line ->
        val language = if (translated) targetLanguage
            else line.sourceLanguage.takeIf { languageTag(it) != null } ?: sourceFallback
        val tag = languageTag(language) ?: return@flatMap emptyList()
        val text = (if (translated) line.translated else line.original).orEmpty().trim()
        splitForSpeech(text).map { PhotoSpeechPhrase(it, tag) }
    }

    private fun languageTag(code: String): String? = when (code) {
        "zh" -> "zh-TW"
        "en" -> "en-US"
        "ja" -> "ja-JP"
        "th" -> "th-TH"
        else -> null
    }

    private fun splitForSpeech(text: String): List<String> {
        val parts = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotBlank()) {
            if (remaining.length <= MAX_SPEECH_CHUNK) {
                parts += remaining.trim()
                break
            }
            val boundary = remaining.take(MAX_SPEECH_CHUNK).indexOfLast {
                it.isWhitespace() || it in "。！？.!?；;"
            }
            val cut = if (boundary >= MAX_SPEECH_CHUNK / 2) boundary + 1
                else MAX_SPEECH_CHUNK
            val part = remaining.take(cut).trim()
            if (part.isNotEmpty()) parts += part
            remaining = remaining.drop(cut).trimStart()
        }
        return parts
    }
}
