package com.example.cameratranslator.core

import kotlin.math.max
import kotlin.math.min

/**
 * Resolve cross-engine OCR conflicts without requiring Android Rect in tests.
 * Intersection is measured relative to the smaller box, since a Thai text
 * line may be enclosed by a larger, incorrectly classified Paddle region.
 */
internal object PhotoOcrOverlapPolicy {
    fun significantOverlap(
        aLeft: Int, aTop: Int, aRight: Int, aBottom: Int,
        bLeft: Int, bTop: Int, bRight: Int, bBottom: Int
    ): Boolean {
        val aWidth = aRight - aLeft
        val aHeight = aBottom - aTop
        val bWidth = bRight - bLeft
        val bHeight = bBottom - bTop
        if (aWidth <= 0 || aHeight <= 0 || bWidth <= 0 || bHeight <= 0) return false
        val width = min(aRight, bRight) - max(aLeft, bLeft)
        val height = min(aBottom, bBottom) - max(aTop, bTop)
        if (width <= 0 || height <= 0) return false
        val smaller = min(aWidth.toLong() * aHeight, bWidth.toLong() * bHeight)
        return width.toLong() * height >= smaller * 0.60
    }
}
