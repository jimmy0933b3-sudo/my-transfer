package com.example.cameratranslator.core.models

import android.graphics.Rect

data class TextElement(
    val id: String,
    val originalText: String,
    val translatedText: String? = null,
    /** Actual provider that returned this translation. OCR-only has no provider. */
    val translationEngine: String? = null,
    val boundingBox: Rect?,
    val sourceLanguage: String = "en",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    /** CameraX has already converted live-camera bounds to PreviewView pixel coordinates. */
    val isViewCoordinates: Boolean = false,
    /** OCR model recognition confidence if supplied (Paddle); null for ML Kit. */
    val ocrConfidence: Float? = null,
    /** Cross-check found a credible alternate reading; don't paint unchecked text. */
    val ocrReviewNeeded: Boolean = false
)
