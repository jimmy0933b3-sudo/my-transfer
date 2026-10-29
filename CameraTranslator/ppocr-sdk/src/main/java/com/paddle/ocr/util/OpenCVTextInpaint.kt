package com.paddle.ocr.util

import android.graphics.Bitmap
import android.graphics.Rect
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * Telea background reconstruction. Input and mask are limited to the supplied
 * ROI: never allocate multiple full-photo native matrices for a small caption.
 * The caller owns the returned bitmap and must recycle it.
 */
object OpenCVTextInpaint {
    @Volatile private var libraryReady = false

    @Synchronized
    private fun initialize(): Boolean {
        if (libraryReady) return true
        libraryReady = try {
            OpenCVLoader.initDebug()
        } catch (_: RuntimeException) {
            false
        } catch (_: UnsatisfiedLinkError) {
            false
        }
        return libraryReady
    }

    /** Compatibility wrapper used by existing instrumented tests. */
    fun inpaint(original: Bitmap, mask: Bitmap): Bitmap? =
        inpaintRegion(original, Rect(0, 0, original.width, original.height), mask)

    /**
     * Mask coordinates are LOCAL to [region]. Failed operations return null;
     * they never write onto original or the destination photo.
     */
    fun inpaintRegion(original: Bitmap, region: Rect, mask: Bitmap): Bitmap? {
        if (region.left < 0 || region.top < 0 ||
            region.right > original.width || region.bottom > original.height ||
            region.width() <= 0 || region.height() <= 0 ||
            region.width() != mask.width || region.height() != mask.height
        ) return null
        // Explicit bound prevents pathologically large native allocations.
        if (region.width().toLong() * region.height() > 1_600_000L) return null
        if (!initialize()) return null

        var crop: Bitmap? = null
        val sourceRgba = Mat()
        val sourceRgb = Mat()
        val maskRgba = Mat()
        val maskGray = Mat()
        val resultRgb = Mat()
        val resultRgba = Mat()
        return try {
            crop = Bitmap.createBitmap(original, region.left, region.top,
                region.width(), region.height())
            Utils.bitmapToMat(crop, sourceRgba)
            Imgproc.cvtColor(sourceRgba, sourceRgb, Imgproc.COLOR_RGBA2RGB)
            Utils.bitmapToMat(mask, maskRgba)
            Imgproc.cvtColor(maskRgba, maskGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(maskGray, maskGray, 127.0, 255.0, Imgproc.THRESH_BINARY)
            Photo.inpaint(sourceRgb, maskGray, resultRgb, 3.0, Photo.INPAINT_TELEA)
            Imgproc.cvtColor(resultRgb, resultRgba, Imgproc.COLOR_RGB2RGBA)
            Bitmap.createBitmap(region.width(), region.height(),
                Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(resultRgba, it)
            }
        } catch (_: RuntimeException) {
            null
        } catch (_: UnsatisfiedLinkError) {
            null
        } finally {
            crop?.recycle()
            sourceRgba.release()
            sourceRgb.release()
            maskRgba.release()
            maskGray.release()
            resultRgb.release()
            resultRgba.release()
        }
    }
}
