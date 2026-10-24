package com.example.cameratranslator.core

import kotlin.math.min

/**
 * Bitmap text is drawn at ORIGINAL photo resolution, not Compose dp.
 * The old 70px font cap shrank labels in 3000px photos to tiny characters.
 */
internal object PhotoRenderSizing {
    fun initialFontSize(availableHeight: Float): Float =
        min(320f, (availableHeight * 0.88f).coerceAtLeast(9f))

    fun minimumFontSize(availableHeight: Float): Float =
        if (availableHeight < 18f) 7f else 8f

    fun decrement(currentSize: Float): Float =
        (currentSize / 27f).coerceIn(1.0f, 9.0f)

    fun lineLimit(original: String): Int =
        // A two-line cap rejected short OCR labels when their Chinese
        // translations were longer than the source. Four lines offer an
        // additional fit opportunity without widening OCR rectangles.
        if (original.trim().length <= 24 && '\n' !in original) 4 else 7
}
