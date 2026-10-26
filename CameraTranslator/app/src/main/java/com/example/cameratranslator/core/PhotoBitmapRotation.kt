package com.example.cameratranslator.core

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import com.example.cameratranslator.core.models.OcrPoint

/** Rotate pixels, bounding boxes and OCR quadrilaterals together. */
internal object PhotoBitmapRotation {
    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        require(degrees % 90 == 0)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(degrees.toFloat()) }, true)
    }

    fun clockwise(rect: Rect?, sourceHeight: Int): Rect? =
        rect?.let { Rect(sourceHeight - it.bottom, it.left,
            sourceHeight - it.top, it.right) }

    /**
     * Preserve text-oriented point order, rather than reordering the corners
     * based on the screen. The baseline's direction must rotate with pixels.
     */
    fun clockwise(quad: List<OcrPoint>, sourceHeight: Int): List<OcrPoint> =
        quad.map { OcrPoint(sourceHeight - it.y, it.x) }
}
