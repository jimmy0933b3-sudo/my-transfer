package com.example.cameratranslator.core

/**
 * The captured-photo OCR text, not live camera text nor translated output.
 * Preserve the original OCR ordering and line breaks for copying.
 */
internal object SnapshotOcrClipboard {
    fun all(lines: List<SnapshotTranslationLine>): String = lines
        .map { it.original.trim() }
        .filter { it.isNotBlank() }
        .joinToString(separator = "\n\n")
}
