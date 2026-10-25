package com.example.cameratranslator.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative, pure-Kotlin layout for overlapping OCR text rectangles.
 *
 * A photographed diagonal Japanese headline may have several different lines
 * whose axis-aligned OCR boxes overlap even though the printed glyphs do not.
 * Partition only vertically stacked, similarly sized, strongly x-aligned
 * rectangles. Same-baseline duplicates, tiny ruby annotations, and boxes
 * with insufficient remaining height deliberately retain their original
 * geometry so the renderer's safe overlap rejection still applies.
 *
 * This only moves the placement/clip rectangles. The renderer separately
 * removes ORIGINAL glyphs from the original full OCR boxes before drawing any
 * translations. That two-pass approach avoids re-erasing already painted text.
 */
internal object PhotoTextBoxLayout {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val centerY: Float get() = (top + bottom) / 2f
        val centerX: Float get() = (left + right) / 2f
        val valid: Boolean get() = width >= 16 && height >= 12
    }

    fun partition(boxes: List<Box?>): List<Box?> {
        val result = boxes.toMutableList()
        for (i in result.indices) {
            for (j in i + 1 until result.size) {
                val a = result[i]?.takeIf { it.valid } ?: continue
                val b = result[j]?.takeIf { it.valid } ?: continue
                val xOverlap = min(a.right, b.right) - max(a.left, b.left)
                val yOverlap = min(a.bottom, b.bottom) - max(a.top, b.top)
                if (xOverlap <= 0 || yOverlap <= 0) continue
                val smaller = min(
                    a.width.toLong() * a.height, b.width.toLong() * b.height
                ).coerceAtLeast(1)
                // Thin intersections between staggered/diagonal printed rows
                // can become *larger* fractions after another pair is split.
                // A 0.23 threshold missed the first/third v5 headline rows,
                // making a later renderer overlap reject a valid line.
                // Keep the strict geometric checks below (x-alignment, text
                // scale, center separation and minimum remaining height).
                if (xOverlap.toLong() * yOverlap / smaller.toFloat() <= .17f) continue
                // A corner touch, left/right columns, or neighboring cropped
                // photo labels aren't a vertical paragraph.
                if (xOverlap < min(a.width, b.width) * .72f) continue
                if (abs(a.centerX - b.centerX) > max(a.width, b.width) * .55f) continue
                val smallerHeight = min(a.height, b.height)
                if (max(a.height, b.height) > smallerHeight * 2.15f) continue
                // Near-identical baselines likely indicate duplicate OCR or
                // overlapping ruby, not two distinct printed rows.
                if (abs(a.centerY - b.centerY) <
                    max(10f, smallerHeight * .24f)) continue
                val upperIndex = if (a.centerY < b.centerY) i else j
                val lowerIndex = if (upperIndex == i) j else i
                val upper = result[upperIndex] ?: continue
                val lower = result[lowerIndex] ?: continue
                val intersectionTop = max(upper.top, lower.top)
                val intersectionBottom = min(upper.bottom, lower.bottom)
                if (intersectionBottom - intersectionTop < 2) continue

                val split = ((upper.centerY + lower.centerY) / 2f).toInt()
                    .coerceIn(intersectionTop + 1, intersectionBottom - 1)
                val upperHeight = split - upper.top
                val lowerHeight = lower.bottom - split
                // Avoid a 3-pixel slot that cannot fit even small Chinese.
                if (upperHeight < max(18f, upper.height * .25f) ||
                    lowerHeight < max(18f, lower.height * .25f)) continue
                result[upperIndex] = upper.copy(bottom = split)
                result[lowerIndex] = lower.copy(top = split)
            }
        }
        return result
    }
}
