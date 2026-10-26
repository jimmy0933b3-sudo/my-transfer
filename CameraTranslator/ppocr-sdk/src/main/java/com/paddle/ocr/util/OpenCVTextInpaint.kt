package com.paddle.ocr.util

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * OpenCV Telea inpainting for a union of printed-text quadrilateral masks.
 *
 * The caller supplies an opaque black mask with white masked text polygons.
 * Never return a half-rendered bitmap. A missing native library or invalid
 * OpenCV input returns null so the app can keep the original photo untouched.
 */
object OpenCVTextInpaint {
    fun inpaint(original: Bitmap, mask: Bitmap): Bitmap? {
        if (original.width != mask.width || original.height != mask.height) return null
        val nativeReady = try {
            OpenCVLoader.initDebug()
        } catch (_: RuntimeException) {
            false
        } catch (_: UnsatisfiedLinkError) {
            false
        }
        if (!nativeReady) return null
        val srcRgba = Mat()
        val srcRgb = Mat()
        val maskRgba = Mat()
        val maskGray = Mat()
        val resultRgb = Mat()
        val resultRgba = Mat()
        return try {
            Utils.bitmapToMat(original, srcRgba)
            Imgproc.cvtColor(srcRgba, srcRgb, Imgproc.COLOR_RGBA2RGB)
            Utils.bitmapToMat(mask, maskRgba)
            Imgproc.cvtColor(maskRgba, maskGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(maskGray, maskGray, 127.0, 255.0,
                Imgproc.THRESH_BINARY)
            // All masked polygons are handled together. Sampling the image
            // once prevents an earlier restored row from contaminating the
            // reconstruction of the following diagonal row.
            Photo.inpaint(srcRgb, maskGray, resultRgb, 3.0, Photo.INPAINT_TELEA)
            Imgproc.cvtColor(resultRgb, resultRgba, Imgproc.COLOR_RGB2RGBA)
            Bitmap.createBitmap(original.width, original.height,
                Bitmap.Config.ARGB_8888).also { output ->
                Utils.matToBitmap(resultRgba, output)
            }
        } catch (_: RuntimeException) {
            null
        } catch (_: UnsatisfiedLinkError) {
            null
        } finally {
            srcRgba.release()
            srcRgb.release()
            maskRgba.release()
            maskGray.release()
            resultRgb.release()
            resultRgba.release()
        }
    }
}
