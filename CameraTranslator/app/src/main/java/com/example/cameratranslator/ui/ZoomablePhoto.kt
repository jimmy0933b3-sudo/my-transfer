package com.example.cameratranslator.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Shared, non-destructive still-photo viewer.
 *
 * Pinch zooms up to 5x around the gesture centroid, drag pans the bitmap,
 * and double tap zooms into the touched position or resets to fit.
 *
 * The zoom state belongs to this composable, not the Bitmap: switching
 * original/translated images keeps the same viewport and zoom position.
 * It is reset only when the photo result screen is closed.
 */
@Composable
internal fun ZoomablePhoto(
    bitmap: Bitmap,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    fun clampPan(candidate: Offset, scale: Float): Offset {
        val w = viewport.width.toFloat()
        val h = viewport.height.toFloat()
        if (w <= 0f || h <= 0f || bitmap.width <= 0 || bitmap.height <= 0) return Offset.Zero
        // ContentScale.Fit means the unzoomed photo may be letterboxed.
        // Clamp using the actual fitted PHOTO, not the outer layout box.
        val fit = min(w / bitmap.width, h / bitmap.height)
        val displayedW = bitmap.width * fit * scale
        val displayedH = bitmap.height * fit * scale
        val limitX = max(0f, (displayedW - w) / 2f)
        val limitY = max(0f, (displayedH - h) / 2f)
        return Offset(
            candidate.x.coerceIn(-limitX, limitX),
            candidate.y.coerceIn(-limitY, limitY)
        )
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .background(Color(0xFF101819))
            .onSizeChanged { viewport = it }
            .pointerInput(bitmap) {
                detectTransformGestures(panZoomLock = true) { centroid, drag, gestureZoom, _ ->
                    val oldZoom = zoom
                    val newZoom = (oldZoom * gestureZoom).coerceIn(1f, 5f)
                    val ratio = newZoom / oldZoom
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val nextPan = pan * ratio + (centroid - center) * (1f - ratio) + drag
                    zoom = newZoom
                    pan = clampPan(nextPan, newZoom)
                }
            }
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = { location ->
                    if (zoom > 1.05f) {
                        zoom = 1f
                        pan = Offset.Zero
                    } else {
                        val targetZoom = 2.5f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        pan = clampPan(
                            pan * (targetZoom / zoom) +
                                (location - center) * (1f - targetZoom / zoom),
                            targetZoom
                        )
                        zoom = targetZoom
                    }
                })
            }
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = pan.x
                translationY = pan.y
            }
        )
        Text(
            "雙指縮放 · 拖曳移動 · 雙擊放大／還原",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
        if (zoom > 1.01f) {
            TextButton(
                onClick = {
                    zoom = 1f
                    pan = Offset.Zero
                },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(10.dp))
            ) {
                Text("還原大小", color = Color.White)
            }
        }
    }
}
