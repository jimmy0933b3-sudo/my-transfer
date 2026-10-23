package com.example.cameratranslator.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.example.cameratranslator.core.models.TextElement
import kotlin.math.max
import kotlin.math.min

/**
 * Produces an actual composited photo. Rendered text is rasterized into
 * bitmap pixels, not a floating Compose UI overlay.
 *
 * The background is approximated from nearby pixels. No claim is made that
 * it perfectly reconstructs complex artwork behind the original lettering.
 */
internal object PhotoTranslationRenderer {
    data class Result(val bitmap: Bitmap, val placed: Int, val skipped: Int)

    fun compose(original: Bitmap, lines: List<Pair<TextElement, String>>): Result {
        val output = original.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("Cannot allocate translated photo")
        val canvas = Canvas(output)
        var placed = 0
        var skipped = 0
        for ((element, translated) in lines) {
            val box = element.boundingBox
            if (box == null || translated.isBlank() ||
                translated.trim() == element.originalText.trim()) continue
            val rect = Rect(box)
            if (!rect.intersect(0, 0, output.width, output.height) ||
                !isUsableRect(rect, output.width, output.height)) {
                skipped++
                continue
            }
            val pixelColor = nearbyBackgroundColor(original, rect)
            val inkColor = if (luma(pixelColor) > 135f) Color.rgb(25, 25, 28)
                else Color.WHITE
            val pad = (min(rect.width(), rect.height()) * 0.055f).coerceIn(2f, 12f)
            val availableWidth = rect.width() - 2 * pad
            val availableHeight = rect.height() - 2 * pad
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = inkColor
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.LEFT
            }
            var chosenLines: List<String>? = null
            var lineSpacing = 0f
            var fontSize = min(70f, availableHeight * 0.8f).coerceAtLeast(8f)
            while (fontSize >= 9f) {
                paint.textSize = fontSize
                val wrapped = wrap(translated.trim().replace('\n', ' '), paint,
                    availableWidth.coerceAtLeast(5f))
                val spacing = fontSize * 1.17f
                if (wrapped.isNotEmpty() && wrapped.size <= 6 &&
                    wrapped.size * spacing <= availableHeight &&
                    wrapped.all { paint.measureText(it) <= availableWidth + 1f }) {
                    chosenLines = wrapped
                    lineSpacing = spacing
                    break
                }
                fontSize -= 1.5f
            }
            if (chosenLines == null) {
                skipped++
                continue
            }
            // Erase the original ink and draw localized replacement.
            canvas.drawRect(rect, Paint().apply { color = pixelColor })
            val overallHeight = chosenLines.size * lineSpacing
            val startY = rect.top + (rect.height() - overallHeight) / 2f +
                fontSize * 0.89f
            chosenLines.forEachIndexed { i, line ->
                canvas.drawText(
                    line,
                    rect.left + (rect.width() - paint.measureText(line)) / 2f,
                    startY + i * lineSpacing,
                    paint
                )
            }
            placed++
        }
        return Result(output, placed, skipped)
    }

    fun isUsableRect(rect: Rect, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || rect.width() < 16 || rect.height() < 12) return false
        val area = rect.width().toLong() * rect.height()
        val full = width.toLong() * height
        // Reject fake full-frame bounding boxes, notably from old Thai OCR.
        return area < full * 0.45f
    }

    private fun nearbyBackgroundColor(bitmap: Bitmap, rect: Rect): Int {
        val points = mutableListOf<Int>()
        val w = bitmap.width
        val h = bitmap.height
        val stepX = max(1, rect.width() / 12)
        val stepY = max(1, rect.height() / 6)
        val top = (rect.top - 3).coerceIn(0, h - 1)
        val bottom = (rect.bottom + 2).coerceIn(0, h - 1)
        val left = (rect.left - 3).coerceIn(0, w - 1)
        val right = (rect.right + 2).coerceIn(0, w - 1)
        for (x in rect.left until rect.right step stepX) {
            points.add(bitmap.getPixel(x.coerceIn(0, w - 1), top))
            points.add(bitmap.getPixel(x.coerceIn(0, w - 1), bottom))
        }
        for (y in rect.top until rect.bottom step stepY) {
            points.add(bitmap.getPixel(left, y.coerceIn(0, h - 1)))
            points.add(bitmap.getPixel(right, y.coerceIn(0, h - 1)))
        }
        if (points.isEmpty()) return Color.WHITE
        val r = points.map(Color::red).sorted()[points.size / 2]
        val g = points.map(Color::green).sorted()[points.size / 2]
        val b = points.map(Color::blue).sorted()[points.size / 2]
        return Color.rgb(r, g, b)
    }

    private fun luma(c: Int): Float =
        (Color.red(c) * .299f + Color.green(c) * .587f + Color.blue(c) * .114f)

    private fun wrap(value: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        var row = ""
        for (character in value) {
            if (character == '\r') continue
            val proposed = row + character
            if (row.isNotEmpty() && paint.measureText(proposed) > maxWidth) {
                result.add(row.trim())
                row = character.toString().trimStart()
            } else {
                row = proposed
            }
        }
        if (row.isNotBlank()) result.add(row.trim())
        return result
    }
}
