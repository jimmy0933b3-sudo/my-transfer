package com.example.cameratranslator.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import com.example.cameratranslator.core.models.TextElement
import com.example.cameratranslator.core.models.OcrPoint
import com.paddle.ocr.util.OpenCVTextInpaint
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
        val paint: Paint,
        val skewedQuad: List<OcrPoint>? = null
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
        val occupiedPolygons = mutableListOf<List<OcrPoint>>()
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
            // Paddle supplies a tight rotated quadrilateral. The old
            // horizontal layout erased neighboring diagonal rows even when
            // the actual printed glyphs never intersected.
            val rotatedQuad = element.textQuad?.takeIf {
                // Paddle supplies both horizontal and tilted text polygons.
                // Both must use the same native repair path.
                PhotoQuadGeometry.paintable(it)
            }
            val planned = if (rotatedQuad == null) layout.getOrNull(index) else null
            val rect = if (planned != null)
                Rect(planned.left, planned.top, planned.right, planned.bottom)
            else Rect(fullRect)
            val polygon = rotatedQuad ?: PhotoQuadGeometry.rectangle(
                rect.left, rect.top, rect.right, rect.bottom)
            // A partitioned fallback rectangle may be narrower than the
            // original OCR text. Erasing the full source would damage neighbors.
            if (rotatedQuad == null && rect != fullRect) {
                skip("無四邊形的文字區域重疊，安全保留原文", index, element)
                continue
            }
            if (!isUsableRect(rect, output.width, output.height) ||
                occupiedPolygons.any { painted ->
                    PhotoQuadGeometry.overlapFraction(polygon, painted) > .23f
                }) {
                skip("文字區塊重疊", index, element)
                continue
            }
            val pixelColor = nearbyBackgroundColor(original, fullRect)
            val inkColor = estimateInkColor(original, fullRect, pixelColor)
            val actualWidth = rotatedQuad?.let(PhotoQuadGeometry::lineWidth)
                ?: rect.width().toFloat()
            val actualHeight = rotatedQuad?.let(PhotoQuadGeometry::lineHeight)
                ?: rect.height().toFloat()
            val pad = (min(actualWidth, actualHeight) * .055f).coerceIn(2f, 12f)
            val availableWidth = actualWidth - 2 * pad
            val availableHeight = actualHeight - 2 * pad
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
            occupiedPolygons += polygon
            if (rect != fullRect) partitionsUsed++
            prepared.add(Prepared(index, element, fullRect, rect,
                chosenLines, lineSpacing, fontSize, paint, rotatedQuad))
        }

        // Plan small, independently repairable ROIs. Any region exceeding
        // the native memory budget is left unchanged, rather than creating
        // several full-photo Bitmaps or painting over surviving English.
        val regions = PhotoInpaintRegionPlanner.plan(
            prepared.map { item ->
                val box = item.fullRect
                PhotoInpaintRegionPlanner.Bounds(
                    box.left, box.top, box.right, box.bottom)
            }, original.width, original.height
        )
        val failedRepairs = regions.rejectedIndices.toMutableSet()
        var repairedPixels = 0L
        val repairStarted = SystemClock.elapsedRealtime()
        for (region in regions.regions) {
            val bounds = region.bounds
            val mask = Bitmap.createBitmap(bounds.width, bounds.height,
                Bitmap.Config.ARGB_8888)
            val patched: Bitmap?
            try {
                val maskCanvas = Canvas(mask)
                maskCanvas.drawColor(Color.BLACK)
                val fill = Paint().apply {
                    isAntiAlias = false
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                val edge = Paint(fill).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f
                    strokeJoin = Paint.Join.ROUND
                }
                maskCanvas.save()
                maskCanvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
                region.indices.forEach { index ->
                    val item = prepared[index]
                    val box = item.fullRect
                    val shape = item.skewedQuad?.let(::quadPath) ?: Path().apply {
                        addRect(box.left.toFloat(), box.top.toFloat(),
                            box.right.toFloat(), box.bottom.toFloat(),
                            Path.Direction.CW)
                    }
                    maskCanvas.drawPath(shape, fill)
                    maskCanvas.drawPath(shape, edge)
                }
                maskCanvas.restore()
                patched = OpenCVTextInpaint.inpaintRegion(original,
                    Rect(bounds.left, bounds.top, bounds.right, bounds.bottom), mask)
            } finally {
                mask.recycle()
            }
            if (patched == null) {
                failedRepairs.addAll(region.indices)
                DiagnosticLog.record("PHOTO_RENDER",
                    "區域修補失敗；原圖保留；像素=" + bounds.pixels +
                        "；段數=" + region.indices.size)
            } else {
                canvas.drawBitmap(patched, bounds.left.toFloat(),
                    bounds.top.toFloat(), null)
                patched.recycle()
                repairedPixels += bounds.pixels
            }
        }
        val inpaintSucceeded = failedRepairs.isEmpty()
        DiagnosticLog.record("PHOTO_RENDER",
            "局部背景修補耗時=" +
                (SystemClock.elapsedRealtime() - repairStarted) +
                "ms；區域數=" + regions.regions.size +
                "；處理像素=" + repairedPixels +
                "；安全保留段數=" + failedRepairs.size)
        var painted = 0
        for (item in prepared) {
            if (prepared.indexOf(item) in failedRepairs) {
                skip("區域過大或 OpenCV 修補失敗，保留原文",
                    item.index, item.element)
                continue
            }
            val rect = item.layoutRect
            val paint = item.paint
            paint.textSize = item.fontSize
            paint.color = readableInkColor(output,
                item.skewedQuad ?: PhotoQuadGeometry.rectangle(
                    rect.left, rect.top, rect.right, rect.bottom))
            val overallHeight = item.translatedLines.size * item.lineSpacing
            canvas.save()
            val quad = item.skewedQuad
            val width = quad?.let(PhotoQuadGeometry::lineWidth) ?: rect.width().toFloat()
            val height = quad?.let(PhotoQuadGeometry::lineHeight) ?: rect.height().toFloat()
            if (quad != null) {
                canvas.clipPath(quadPath(quad))
                canvas.translate(quad[0].x, quad[0].y)
                canvas.rotate(PhotoQuadGeometry.angleDegrees(quad))
            } else {
                canvas.clipRect(rect)
                canvas.translate(rect.left.toFloat(), rect.top.toFloat())
            }
            val startY = (height - overallHeight) / 2f + item.fontSize * .89f
            item.translatedLines.forEachIndexed { i, line ->
                canvas.drawText(
                    line, (width - paint.measureText(line)) / 2f,
                    startY + i * item.lineSpacing, paint)
            }
            canvas.restore()
            painted++
            DiagnosticLog.record("PHOTO_RENDER",
                "編號=" + blockIndices[item.index] + "；結果=成功；框=" +
                    item.fullRect.toShortString() +
                    "；排版框=" + rect.toShortString() +
                    "；重疊分區=" + (item.fullRect != rect) +
                    "；斜向四邊形=" + (item.skewedQuad != null) +
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
            "可畫段數=" + painted + "；局部背景修補成功=" + inpaintSucceeded +
                "；重疊重新分區=" + partitionsUsed +
            "；因重疊保留原文=" + (skipReasons["文字區塊重疊"] ?: 0))
        return Result(output, painted, skipped, skipReasons, unchanged)
    }

    private fun quadPath(quad: List<OcrPoint>): Path = Path().apply {
        moveTo(quad[0].x, quad[0].y)
        for (i in 1..3) lineTo(quad[i].x, quad[i].y)
        close()
    }

    /** Choose legible ink from reconstructed background, not from the
     * horizontal OCR Rect which also includes neighboring slanted rows.
     */
    private fun readableInkColor(bitmap: Bitmap, quad: List<OcrPoint>): Int {
        val p0 = quad[0]
        val p1 = quad[1]
        val p2 = quad[2]
        val p3 = quad[3]
        var lumaSum = 0f
        var count = 0
        // Sample along the CENTER of the healed line, away from other lines.
        for (i in 1..7) {
            val u = i / 8f
            val x = ((p0.x + p3.x) * .5f * (1f - u) +
                (p1.x + p2.x) * .5f * u).toInt()
                .coerceIn(0, bitmap.width - 1)
            val y = ((p0.y + p3.y) * .5f * (1f - u) +
                (p1.y + p2.y) * .5f * u).toInt()
                .coerceIn(0, bitmap.height - 1)
            lumaSum += luma(bitmap.getPixel(x, y))
            count++
        }
        return if (lumaSum / count > 135f) Color.rgb(20, 25, 30)
            else Color.WHITE
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
