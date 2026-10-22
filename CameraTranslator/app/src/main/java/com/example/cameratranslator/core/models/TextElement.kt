package com.example.cameratranslator.core.models

import android.graphics.Rect

data class TextElement(
    val id: String,
    val originalText: String,
    val translatedText: String? = null,
    val boundingBox: Rect?,
    val sourceLanguage: String = "en",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    /** CameraX has already converted live-camera bounds to PreviewView pixel coordinates. */
    val isViewCoordinates: Boolean = false
)
