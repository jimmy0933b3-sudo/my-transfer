package com.example.cameratranslator.core

/** One full OCR text block and the translation of exactly that block. */
data class SnapshotTranslationLine(
    val original: String,
    val sourceLanguage: String = "auto",
    val translated: String? = null,
    val provider: String = "辨識原文"
)
