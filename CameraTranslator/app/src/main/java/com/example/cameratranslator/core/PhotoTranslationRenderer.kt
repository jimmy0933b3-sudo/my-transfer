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
    data class Result(
        val bitmap: Bitmap,
        val placed: Int,
        val skipped: Int,
        val skipReasons: Map<String, Int> = emptyMap()
    )

    fun compose(original: Bitmap, lines: List<Pair<TextElement, String>>): Result {
        val output = original.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("Cannot allocate translated photo")
        val canvas = Canvas(output)
        var placed = 0
        var skipped = 0
        val skipReasons = linkedMapOf<String, Int>()
        fun skip(reason: String) {
            skipped++
            skipReasons[reason] = (skipReasons[reason] ?: 0) + 1
        }
        val occupied = mutableListOf<Rect>()
        // Always compose directly into the actual captured Bitmap. No Compose
        // overlay is added to the exported or shared photo.
        for ((element, translated) in lines) {
            val box = element.boundingBox
            if (translated.isBlank() ||
                translated.trim() == element.originalText.trim()) continue
            if (box == null) {
                skip("沒有文字座標")
                continue
            }
            val rect = Rect(box)
            if (!rect.intersect(0, 0, output.width, output.height) ||
                !isUsableRect(rect, output.width, output.height)) {
                skip("無效文字座標")
                continue
            }
            // OCR engines can produce overlapping line and block boxes.
            // Replacing both would erase the first translation with the
            // second block's background reconstruction.
            if (occupied.any { painted -> overlapFraction(rect, painted) > 0.23f }) {
                skip("文字區塊重疊")
                continue
            }
            val pixelColor = nearbyBackgroundColor(original, rect)
            val inkColor = estimateInkColor(original, rect, pixelColor)
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
            // Use the photo's ORIGINAL pixel coordinates: a 70px cap made
            // four Chinese characters tiny inside 200px-tall product titles.
            var fontSize = PhotoRenderSizing.initialFontSize(availableHeight)
            val maxLines = PhotoRenderSizing.lineLimit(element.originalText)
            val minimumFont = PhotoRenderSizing.minimumFontSize(availableHeight)
            while (fontSize >= minimumFont) {
                paint.textSize = fontSize
                val wrapped = wrap(translated.trim().replace('\n', ' '), paint,
                    availableWidth.coerceAtLeast(5f))
                val spacing = fontSize * 1.16f
                if (wrapped.isNotEmpty() && wrapped.size <= maxLines &&
                    wrapped.size * spacing <= availableHeight &&
                    wrapped.all { paint.measureText(it) <= availableWidth + 1f }) {
                    chosenLines = wrapped
                    lineSpacing = spacing
                    break
                }
                fontSize -= PhotoRenderSizing.decrement(fontSize)
            }
            if (chosenLines == null) {
                skip("翻譯字數超出文字框")
                continue
            }
            // Erase the original glyphs from the image pixels *before*
            // drawing the new glyphs at those exact bitmap coordinates.
            eraseTextRegion(output, original, rect)
            val overallHeight = chosenLines.size * lineSpacing
            val startY = rect.top + (rect.height() - overallHeight) / 2f +
                fontSize * 0.89f
            canvas.save()
            canvas.clipRect(rect)
            chosenLines.forEachIndexed { i, line ->
                canvas.drawText(
                    line,
                    rect.left + (rect.width() - paint.measureText(line)) / 2f,
                    startY + i * lineSpacing,
                    paint
                )
            }
            canvas.restore()
            occupied.add(Rect(rect))
            placed++
        }
        return Result(output, placed, skipped, skipReasons)
    }

    fun isUsableRect(rect: Rect, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || rect.width() < 16 || rect.height() < 12) return false
        val area = rect.width().toLong() * rect.height()
        val full = width.toLong() * height
        // Reject fake full-frame bounding boxes, notably from old Thai OCR.
        return area < full * 0.45f
    }


    /** Overlap measured against the smaller OCR box, not the whole photo. */
    private fun overlapFraction(a: Rect, b: Rect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (left >= right || top >= bottom) return 0f
        val smaller = min(
            a.width().toLong() * a.height(),
            b.width().toLong() * b.height()
        ).coerceAtLeast(1)
        return ((right - left).toLong() * (bottom - top)) / smaller.toFloat()
    }

    /**
     * Approximate the actual ink color from interior pixels that differ most
     * from the nearby background. Fall back to legible black/white if the
     * region is too uniform to sample reliably.
     */
    private fun estimateInkColor(bitmap: Bitmap, rect: Rect, background: Int): Int {
        val samples = mutableListOf<Pair<Float, Int>>()
        val stepX = max(1, rect.width() / 20)
        val stepY = max(1, rect.height() / 10)
        for (y in rect.top + 1 until rect.bottom - 1 step stepY) {
            for (x in rect.left + 1 until rect.right - 1 step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val distance = kotlin.math.abs(Color.red(pixel) - Color.red(background)) +
                    kotlin.math.abs(Color.green(pixel) - Color.green(background)) +
                    kotlin.math.abs(Color.blue(pixel) - Color.blue(background))
                samples.add(distance.toFloat() to pixel)
            }
        }
        val highContrast = samples.sortedByDescending { it.first }
            .take(max(1, samples.size / 8))
            .filter { it.first >= 95f }
        if (highContrast.isEmpty()) {
            return if (luma(background) > 135f) Color.rgb(25, 25, 28)
            else Color.WHITE
        }
        val r = highContrast.map { Color.red(it.second) }.average().toInt()
        val g = highContrast.map { Color.green(it.second) }.average().toInt()
        val b = highContrast.map { Color.blue(it.second) }.average().toInt()
        val result = Color.rgb(r, g, b)
        if (kotlin.math.abs(luma(result) - luma(background)) < 48f) {
            return if (luma(background) > 135f) Color.rgb(25, 25, 28)
            else Color.WHITE
        }
        return result
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

    /**
     * Lightweight 4-edge background reconstruction. Different from ML/AI
     * inpainting, but smoother for gradients and package backgrounds than
     * a plain grey/black OCR box.
     */
    private fun eraseTextRegion(target: Bitmap, original: Bitmap, rect: Rect) {
        val width = rect.width()
        val height = rect.height()
        if (width <= 0 || height <= 0) return
        val pixels = IntArray(width * height)
        // OCR bounds hug the glyphs. Sampling only 2-3px outside often
        // picks up the dark ink/outline and creates a muddy gray rectangle.
        // Sample farther into the label's actual surrounding background.
        val margin = (min(width, height) * 0.085f).toInt().coerceIn(4, 24)
        val l = (rect.left - margin).coerceIn(0, original.width - 1)
        val r = (rect.right + margin).coerceIn(0, original.width - 1)
        val t = (rect.top - margin).coerceIn(0, original.height - 1)
        val b = (rect.bottom + margin).coerceIn(0, original.height - 1)
        for (y in 0 until height) {
            val fy = y.toFloat() / height.coerceAtLeast(1)
            val left = original.getPixel(l, rect.top + y)
            val right = original.getPixel(r, rect.top + y)
            for (x in 0 until width) {
                val fx = x.toFloat() / width.coerceAtLeast(1)
                val top = original.getPixel(rect.left + x, t)
                val bottom = original.getPixel(rect.left + x, b)
                val vertical = blend(top, bottom, fy)
                val horizontal = blend(left, right, fx)
                pixels[y * width + x] = blend(vertical, horizontal, 0.5f)
            }
        }
        target.setPixels(pixels, 0, width, rect.left, rect.top, width, height)
    }

    private fun blend(a: Int, b: Int, ratio: Float): Int {
        val t = ratio.coerceIn(0f, 1f)
        val r = (Color.red(a) * (1 - t) + Color.red(b) * t).toInt().coerceIn(0, 255)
        val g = (Color.green(a) * (1 - t) + Color.green(b) * t).toInt().coerceIn(0, 255)
        val bl = (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, bl)
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
