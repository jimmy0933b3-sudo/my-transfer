package com.example.cameratranslator.overlay

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cameratranslator.core.models.TextElement
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun TranslationOverlay(
    textElements: List<TextElement>,
    showOriginal: Boolean,
    paused: Boolean,
    opacity: Float = 0.88f,
    onCopy: (String) -> Unit = {}
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidth = with(density) { maxWidth.toPx() }
        val screenHeight = with(density) { maxHeight.toPx() }
        val margin = with(density) { 8.dp.toPx() }
        val safeTop = with(density) { 150.dp.toPx() }
        val safeBottom = screenHeight - with(density) { 88.dp.toPx() }
        val minCard = with(density) { 135.dp.toPx() }
        val maxCard = with(density) { 280.dp.toPx() }
        val linePixels = with(density) { 20.sp.toPx() }
        val used = mutableListOf<RectF>()

        textElements.sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }.forEach { element ->
            val box = element.boundingBox ?: return@forEach
            // Raw OCR must NOT masquerade as a translated overlay when
            // translation is pending, failed, or the user paused the camera.
            // Original text is displayed in its own readable list in MainScreen.
            if (showOriginal) return@forEach
            val text = element.translatedText.orEmpty().trim()
            if (text.isEmpty()) return@forEach

            val rect = if (element.isViewCoordinates) {
                RectF(box) // Already in PreviewView pixel coordinates. Do NOT rescale a second time.
            } else {
                if (element.imageWidth <= 0 || element.imageHeight <= 0) return@forEach
                val scale = min(screenWidth / element.imageWidth, screenHeight / element.imageHeight)
                val dx = (screenWidth - element.imageWidth * scale) / 2f
                val dy = (screenHeight - element.imageHeight * scale) / 2f
                RectF(
                    box.left * scale + dx, box.top * scale + dy,
                    box.right * scale + dx, box.bottom * scale + dy
                )
            }
            if (rect.bottom <= 0 || rect.top >= screenHeight ||
                rect.right <= 0 || rect.left >= screenWidth) return@forEach

            val cardWidth = min(screenWidth - 2 * margin,
                min(maxCard, max(minCard, rect.width() + with(density) { 28.dp.toPx() })))
            if (cardWidth <= 0f) return@forEach
            val x = rect.left.coerceIn(margin, max(margin, screenWidth - margin - cardWidth))
            // Chinese glyphs are close to one full em; underestimating their width
            // caused actual cards to collide with the next translated paragraph.
            val glyphWidth = with(density) { 16.sp.toPx() }
            val charactersPerRow = max(5, ((cardWidth - with(density) { 20.dp.toPx() }) / glyphWidth).toInt())
            val rows = text.split('\n').sumOf {
                max(1, (it.length + charactersPerRow - 1) / charactersPerRow)
            }
            val cardHeight = with(density) { 14.dp.toPx() } + min(4, rows) * linePixels
            var y = rect.top.coerceIn(safeTop, max(safeTop, safeBottom - margin - cardHeight))

            repeat(used.size) {
                val collision = used.firstOrNull {
                    RectF.intersects(it, RectF(x, y, x + cardWidth, y + cardHeight))
                }
                if (collision != null) {
                    y = (collision.bottom + margin)
                        .coerceAtMost(max(safeTop, safeBottom - margin - cardHeight))
                }
            }
            used.add(RectF(x, y, x + cardWidth, y + cardHeight))

            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .width(with(density) { cardWidth.toDp() })
                    .heightIn(min = 38.dp)
                    .background(Color(0xFF10262B).copy(alpha = opacity), RoundedCornerShape(8.dp))
                    .clickable(enabled = paused) { onCopy(text) }
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
