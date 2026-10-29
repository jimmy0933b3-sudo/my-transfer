package com.example.cameratranslator.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paddle.ocr.util.OpenCVTextInpaint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoTextInpaintInstrumentedTest {
    @Test fun slantedTextMaskRestoresBackgroundWithoutChangingOutsidePixels() {
        val original = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val srcCanvas = Canvas(original)
        srcCanvas.drawColor(Color.rgb(100, 130, 160))
        val color = Paint().apply { this.color = Color.WHITE }
        srcCanvas.drawRect(Rect(50, 102, 265, 116), color)
        val mask = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        Canvas(mask).apply {
            drawColor(Color.BLACK)
            drawRect(Rect(48, 100, 268, 118), color)
        }
        val healed = OpenCVTextInpaint.inpaint(original, mask)
        assertNotNull("OpenCV must be available on supported test devices", healed)
        healed!!
        assertEquals(original.getPixel(15, 15), healed.getPixel(15, 15))
        val center = healed.getPixel(130, 110)
        assertTrue(Color.red(center) < 160)
        assertTrue(Color.blue(center) > Color.red(center))
        healed.recycle()
        mask.recycle()
        original.recycle()
    }

    @Test fun regionInpaintingMatchesSourcePixelsOutsideTheMask() {
        val original = Bitmap.createBitmap(420, 300, Bitmap.Config.ARGB_8888)
        original.eraseColor(Color.rgb(90, 140, 170))
        val canvas = Canvas(original)
        val white = Paint().apply { color = Color.WHITE }
        canvas.drawRect(120f, 130f, 240f, 147f, white)
        val region = Rect(100, 110, 260, 170)
        val mask = Bitmap.createBitmap(160, 60, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(mask)
        maskCanvas.drawColor(Color.BLACK)
        maskCanvas.drawRect(20f, 20f, 140f, 37f, white)
        val restored = OpenCVTextInpaint.inpaintRegion(original, region, mask)
        assertNotNull(restored)
        restored!!
        assertEquals(160, restored.width)
        assertEquals(60, restored.height)
        assertEquals(original.getPixel(100, 110), restored.getPixel(0, 0))
        assertTrue(Color.red(restored.getPixel(90, 28)) < 150)
        restored.recycle()
        original.recycle()
        mask.recycle()
    }

    @Test fun mismatchedRegionMaskFailsClosed() {
        val original = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        assertNull(OpenCVTextInpaint.inpaintRegion(original,
            Rect(0, 0, 40, 40), mask))
        mask.recycle()
        original.recycle()
    }
}
