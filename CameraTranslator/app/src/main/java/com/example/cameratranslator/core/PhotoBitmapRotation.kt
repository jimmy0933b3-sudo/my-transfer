package com.example.cameratranslator.core

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect

/** Rotate pixels and bounding boxes together so exports and corrections match. */
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
}
