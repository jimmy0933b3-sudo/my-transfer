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
        val skipReasons: Map<String, Int> = emptyMap(),
        /** Translations identical to OCR text require no pixel replacement. */
        val unchanged: Int = 0
    )

    private data class Prepared(
        val index: Int,
        val element: TextElement,
        val fullRect: Rect,
        val layoutRect: Rect,
        val translatedLines: List<String>,
        val lineSpacing: Float,
        val fontSize: Float,
        val paint: Paint
    )

    fun compose(
        original: Bitmap, lines: List<Pair<TextElement, String>>,
        blockIndices: List<Int> = lines.indices.toList()
    ): Result {
        require(blockIndices.size == lines.size) { "Render block index mismatch" }
        val output = original.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("Cannot allocate translated photo")
        val canvas = Canvas(output)
        var skipped = 0
        var unchanged = 0
        val skipReasons = linkedMapOf<String, Int>()
        fun skip(reason: String, index: Int, element: TextElement) {
            skipped++
            skipReasons[reason] = (skipReasons[reason] ?: 0) + 1
            DiagnosticLog.record("PHOTO_RENDER",
                "編號=" + blockIndices[index] + "；結果=跳過；原因=" + reason +
                    "；框=" + (element.boundingBox?.toShortString() ?: "無"))
            DiagnosticLog.photoResult(blockIndices[index], element.sourceLanguage,
                element.originalText, null, "RENDER_SKIPPED", "原因=" + reason)
        }

        // Plan ALL possible changed translations together before painting.
        // The old single-pass occupied-box check discarded valid overlapping
        // diagonal headline rows and gift-pack sentences from the real photo.
        val usable = lines.map { (element, translated) ->
            val box = element.boundingBox
            if (translated.isBlank() || translated.trim() == element.originalText.trim() ||
                box == null) return@map null
            val clipped = Rect(box)
            if (!clipped.intersect(0, 0, output.width, output.height) ||
                !isUsableRect(clipped, output.width, output.height)) null
            else PhotoTextBoxLayout.Box(
                clipped.left, clipped.top, clipped.right, clipped.bottom)
        }
        val layout = PhotoTextBoxLayout.partition(usable)
        val occupied = mutableListOf<Rect>()
        val prepared = mutableListOf<Prepared>()
        var partitionsUsed = 0
        for ((index, item) in lines.withIndex()) {
            val (element, translated) = item
            if (translated.isBlank()) continue
            if (translated.trim() == element.originalText.trim()) {
                unchanged++
                DiagnosticLog.record("PHOTO_RENDER",
                    "編號=" + blockIndices[index] + "；結果=同原文不須替換")
                DiagnosticLog.photoResult(blockIndices[index], element.sourceLanguage,
                    element.originalText, translated, "RENDER_UNCHANGED", "與原文相同")
                continue
            }
            val bounds = element.boundingBox
            if (bounds == null) {
                skip("沒有文字座標", index, element)
                continue
            }
            val fullRect = Rect(bounds)
            if (!fullRect.intersect(0, 0, output.width, output.height) ||
                !isUsableRect(fullRect, output.width, output.height)) {
                skip("無效文字座標", index, element)
                continue
            }
            val planned = layout.getOrNull(index)
            val rect = if (planned != null)
                Rect(planned.left, planned.top, planned.right, planned.bottom)
            else Rect(fullRect)
            if (!isUsableRect(rect, output.width, output.height) ||
                occupied.any { painted -> overlapFraction(rect, painted) > .23f }) {
                skip("文字區塊重疊", index, element)
                continue
            }
            val pixelColor = nearbyBackgroundColor(original, fullRect)
            val inkColor = estimateInkColor(original, fullRect, pixelColor)
            val pad = (min(rect.width(), rect.height()) * .055f).coerceIn(2f, 12f)
            val availableWidth = rect.width() - 2 * pad
            val availableHeight = rect.height() - 2 * pad
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = inkColor
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.LEFT
            }
            var chosenLines: List<String>? = null
            var lineSpacing = 0f
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
                skip("翻譯字數超出文字框", index, element)
                continue
            }
            occupied.add(Rect(rect))
            if (rect != fullRect) partitionsUsed++
            prepared.add(Prepared(index, element, fullRect, rect,
                chosenLines, lineSpacing, fontSize, paint))
        }

        // Erase ALL accepted source rectangles first. Erasing per line AFTER
        // drawing the previous line destroyed already translated text in
        // overlapping OCR boxes; the original bitmap remains the sampler.
        prepared.forEach { eraseTextRegion(output, original, it.fullRect) }
        for (item in prepared) {
            val rect = item.layoutRect
            val paint = item.paint
            paint.textSize = item.fontSize
            val overallHeight = item.translatedLines.size * item.lineSpacing
            val startY = rect.top + (rect.height() - overallHeight) / 2f +
                item.fontSize * .89f
            canvas.save()
            canvas.clipRect(rect)
            item.translatedLines.forEachIndexed { i, line ->
                canvas.drawText(
                    line,
                    rect.left + (rect.width() - paint.measureText(line)) / 2f,
                    startY + i * item.lineSpacing, paint)
            }
            canvas.restore()
            DiagnosticLog.record("PHOTO_RENDER",
                "編號=" + blockIndices[item.index] + "；結果=成功；框=" +
                    item.fullRect.toShortString() +
                    "；排版框=" + rect.toShortString() +
                    "；重疊分區=" + (item.fullRect != rect) +
                    "；行數=" + item.translatedLines.size +
                    "；字級=" + item.fontSize.toInt())
            DiagnosticLog.photoResult(blockIndices[item.index], item.element.sourceLanguage,
                item.element.originalText, lines[item.index].second,
                "RENDER_PAINTED", "原文框=" + item.fullRect.toShortString() +
                    "；排版框=" + rect.toShortString() +
                    "；行數=" + item.translatedLines.size +
                    "；字級=" + item.fontSize.toInt())
        }
        DiagnosticLog.record("RENDER_LAYOUT",
            "可畫段數=" + prepared.size + "；重疊重新分區=" + partitionsUsed +
            "；因重疊保留原文=" + (skipReasons["文字區塊重疊"] ?: 0))
        return Result(output, prepared.size, skipped, skipReasons, unchanged)
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
        // The earlier 4-edge interpolation copied ONE source pixel from the
        // upper/lower OCR border for every output column. Thai poster glyphs
        // and diacritics touch those boundaries, producing long colored
        // vertical streaks through the translated headline.
        //
        // Instead sample robust patches well OUTSIDE the OCR rectangle at
        // four corners, then reconstruct a smooth bilinear background.
        // This is a conservative gradient reconstruction, not inpainting.
        val margin = (min(width, height) * .13f).toInt().coerceIn(8, 28)
        fun corner(x: Int, y: Int): Int {
            val rr = IntArray(25)
            val gg = IntArray(25)
            val bb = IntArray(25)
            var index = 0
            for (dy in -2..2) for (dx in -2..2) {
                val p = original.getPixel(
                    (x + dx).coerceIn(0, original.width - 1),
                    (y + dy).coerceIn(0, original.height - 1)
                )
                rr[index] = Color.red(p)
                gg[index] = Color.green(p)
                bb[index] = Color.blue(p)
                index++
            }
            rr.sort()
            gg.sort()
            bb.sort()
            return Color.rgb(rr[12], gg[12], bb[12])
        }
        val topLeft = corner(rect.left - margin, rect.top - margin)
        val topRight = corner(rect.right + margin, rect.top - margin)
        val bottomLeft = corner(rect.left - margin, rect.bottom + margin)
        val bottomRight = corner(rect.right + margin, rect.bottom + margin)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val fy = (y + .5f) / height
            for (x in 0 until width) {
                val fx = (x + .5f) / width
                val top = blend(topLeft, topRight, fx)
                val bottom = blend(bottomLeft, bottomRight, fx)
                pixels[y * width + x] = blend(top, bottom, fy)
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
