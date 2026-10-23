package com.example.cameratranslator.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.cameratranslator.core.SnapshotTranslationLine
import kotlin.math.max
import kotlin.math.min

/**
 * Burn successfully translated text onto a COPY of the actual high-resolution
 * still photograph. OCR Rects are always in *photo bitmap pixels*, never
 * PreviewView coordinates. The bitmap beneath stays available as Original.
 *
 * Fonts and backgrounds are approximated locally, NOT claimed to be exact
 * restoration of artwork, curved packaging or stylized lettering.
 */
object PhotoTranslationRenderer {
    fun render(original: Bitmap, lines: List<SnapshotTranslationLine>): Bitmap {
        val output = original.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("Cannot make writable photo")
        val canvas = Canvas(output)
        val maxWidth = output.width
        val maxHeight = output.height
        lines.filter { !it.translated.isNullOrBlank() && it.bounds != null &&
            it.original.trim() != it.translated?.trim() }.forEach { line ->
            val source = Rect(line.bounds!!)
            if (!source.intersect(0, 0, maxWidth, maxHeight)) return@forEach
            if (source.width() < 8 || source.height() < 8) return@forEach
            drawTranslated(canvas, original, source, line.translated.orEmpty())
        }
        return output
    }

    private fun drawTranslated(canvas: Canvas, original: Bitmap, source: Rect, translated: String) {
        val photoWidth = original.width
        val photoHeight = original.height
        // A translated phrase can be longer than the source script. Expand
        // only locally; never relocate it into a list at the screen bottom.
        val width = min((source.width() * 1.4f).toInt().coerceAtLeast(source.width() + 16),
            (photoWidth * 0.78f).toInt().coerceAtLeast(source.width()))
        val x = source.left.coerceIn(0, (photoWidth - width).coerceAtLeast(0))
        val boxWidth = min(width, photoWidth - x)
        if (boxWidth < 20) return

        val maxBlockHeight = (photoHeight * 0.20f).toInt().coerceAtLeast(source.height())
        val availableHeight = max(source.height() + 12, min(maxBlockHeight, source.height() * 3))
        var chosenLayout: StaticLayout? = null
        var chosenPaint: TextPaint? = null
        val minFont = max(15f, photoWidth / 130f)
        val maxFont = max(minFont, min(100f, source.height() * 0.82f))
        for (index in 0..16) {
            val fontSize = maxFont - (maxFont - minFont) * index / 16
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = fontSize
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
                )
            }
            val layout = StaticLayout.Builder.obtain(
                translated, 0, translated.length, paint, (boxWidth - 12).coerceAtLeast(8)
            )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.05f)
                .build()
            chosenLayout = layout
            chosenPaint = paint
            if (layout.height + 12 <= availableHeight) break
        }
        val layout = chosenLayout ?: return
        val textPaint = chosenPaint ?: return
        val height = max(source.height() + 4, layout.height + 12).coerceAtMost(photoHeight)
        val y = source.top.coerceIn(0, (photoHeight - height).coerceAtLeast(0))

        // Sample a neighboring background pixel. A single patch avoids
        // leaking the old characters through the new translated lettering.
        val sampleX = (source.left - 5).coerceIn(0, photoWidth - 1)
        val sampleY = (source.top - 5).coerceIn(0, photoHeight - 1)
        val background = original.getPixel(sampleX, sampleY)
        val rgbMean = (Color.red(background) * 299 +
            Color.green(background) * 587 + Color.blue(background) * 114) / 1000
        textPaint.color = if (rgbMean < 140) Color.WHITE else Color.BLACK

        val patch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(Color.red(background), Color.green(background),
                Color.blue(background))
        }
        canvas.drawRoundRect(
            x.toFloat(), y.toFloat(), (x + boxWidth).toFloat(), (y + height).toFloat(),
            4f, 4f, patch
        )
        canvas.save()
        canvas.translate(x + 6f, y + 6f)
        layout.draw(canvas)
        canvas.restore()
    }
}
