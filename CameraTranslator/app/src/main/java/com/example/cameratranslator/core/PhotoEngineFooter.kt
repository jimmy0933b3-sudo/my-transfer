package com.example.cameratranslator.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Add a narrow provenance strip UNDER the final translated photo.
 *
 * The translated pixels and OCR coordinates are left untouched. The strip is
 * baked into the JPEG only when saved or shared, so recipients can see which
 * OCR and translation providers really produced this result.
 */
internal object PhotoEngineFooter {
    fun append(photo: Bitmap, ocr: String, translation: String): Bitmap {
        val size = (photo.width / 62f).coerceIn(14f, 48f)
        val margin = (size * 1.0f).toInt().coerceAtLeast(12)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val maxTextWidth = (photo.width - margin * 2).toFloat().coerceAtLeast(1f)
        val lines = listOf("OCR：" + ocr, "翻譯：" + translation).flatMap { sentence ->
            val parts = mutableListOf<String>()
            var remaining = sentence
            while (remaining.isNotEmpty()) {
                val count = paint.breakText(remaining, true, maxTextWidth, null)
                    .coerceAtLeast(1)
                parts.add(remaining.take(count))
                remaining = remaining.drop(count)
            }
            parts
        }
        val lineHeight = size * 1.45f
        val extra = (margin * 2 + lineHeight * lines.size).toInt()
        val merged = Bitmap.createBitmap(
            photo.width, photo.height + extra, Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(merged)
        canvas.drawColor(Color.rgb(13, 26, 34))
        canvas.drawBitmap(photo, 0f, 0f, null)
        var baseline = photo.height + margin + size
        for (line in lines) {
            canvas.drawText(line, margin.toFloat(), baseline, paint)
            baseline += lineHeight
        }
        return merged
    }
}
