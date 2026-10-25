package com.example.cameratranslator.core

/**
 * Keep the full OCR/translation text on one diagnostic line without losing
 * newlines, carriage returns, tabs or literal backslashes.
 */
internal object PhotoDiagnosticTextFormatter {
    fun escape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
}
