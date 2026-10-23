package com.example.cameratranslator.core

import kotlin.math.min

/**
 * Bitmap text is drawn at ORIGINAL photo resolution, not Compose dp.
 * The old 70px font cap shrank labels in 3000px photos to tiny characters.
 */
internal object PhotoRenderSizing {
    fun initialFontSize(availableHeight: Float): Float =
        min(320f, (availableHeight * 0.88f).coerceAtLeast(9f))

    fun decrement(currentSize: Float): Float =
        (currentSize / 27f).coerceIn(1.0f, 9.0f)

    fun lineLimit(original: String): Int =
        if (original.trim().length <= 24 && '\n' !in original) 2 else 6
}
