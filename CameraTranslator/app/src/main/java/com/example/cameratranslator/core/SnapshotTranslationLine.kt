package com.example.cameratranslator.core

import android.graphics.Rect

/** One full OCR text block and the translation of exactly that block. */
data class SnapshotTranslationLine(
    val original: String,
    val sourceLanguage: String = "auto",
    /** Rect measured on the actual photographed bitmap, not the preview. */
    val bounds: Rect? = null,
    val translated: String? = null,
    val provider: String = "辨識原文"
)
