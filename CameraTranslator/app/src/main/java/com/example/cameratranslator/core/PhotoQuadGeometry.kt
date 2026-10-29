package com.example.cameratranslator.core

import com.example.cameratranslator.core.models.OcrPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Geometry of four-corner OCR rows (TL, TR, BR, BL). Using the OCR's
 * original skew instead of its enclosing axis-aligned rectangle prevents
 * adjacent diagonal text from being mistaken for overlapping content.
 * All calculations are in original photo pixel coordinates.
 */
internal object PhotoQuadGeometry {
    fun angleDegrees(points: List<OcrPoint>): Float =
        Math.toDegrees(atan2(
            (points[1].y - points[0].y).toDouble(),
            (points[1].x - points[0].x).toDouble()
        )).toFloat()

    fun lineWidth(points: List<OcrPoint>): Float =
        hypot(points[1].x - points[0].x, points[1].y - points[0].y)

    fun lineHeight(points: List<OcrPoint>): Float =
        hypot(points[3].x - points[0].x, points[3].y - points[0].y)

    /** Includes nearly horizontal Paddle text. Separate from skew-angle heuristics. */
    fun paintable(points: List<OcrPoint>?): Boolean {
        if (points?.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() })
            return false
        val w = lineWidth(points)
        val h = lineHeight(points)
        return w >= 24f && h >= 12f && w >= h * 1.5f &&
            polygonArea(points) >= 260f && isConvex(points)
    }

    /** Legacy heuristic retained for title-overprint detection. */
    fun eligible(points: List<OcrPoint>?): Boolean =
        paintable(points) && abs(angleDegrees(points!!)) in 5f..175f

    fun polygonArea(points: List<OcrPoint>): Float {
        var sum = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) * .5f
    }

    private fun isConvex(points: List<OcrPoint>): Boolean {
        var orientation = 0
        for (i in points.indices) {
            val turn = cross(points[i], points[(i + 1) % 4],
                points[(i + 2) % 4])
            if (!turn.isFinite() || abs(turn) < 0.01f) return false
            val direction = if (turn > 0f) 1 else -1
            if (orientation != 0 && orientation != direction) return false
            orientation = direction
        }
        return true
    }

    private fun signedArea(points: List<OcrPoint>): Float {
        var sum = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum * .5f
    }

    private fun cross(a: OcrPoint, b: OcrPoint, p: OcrPoint): Float =
        (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

    fun contains(points: List<OcrPoint>, x: Float, y: Float): Boolean {
        val sign = if (signedArea(points) >= 0f) 1f else -1f
        val p = OcrPoint(x, y)
        return points.indices.all { i ->
            cross(points[i], points[(i + 1) % points.size], p) * sign >= -.1f
        }
    }

    private fun intersect(
        start: OcrPoint, end: OcrPoint, a: OcrPoint, b: OcrPoint
    ): OcrPoint {
        val d1 = cross(a, b, start)
        val d2 = cross(a, b, end)
        val denominator = d1 - d2
        if (abs(denominator) < 0.00001f) return start
        val t = (d1 / denominator).coerceIn(0f, 1f)
        return OcrPoint(start.x + (end.x - start.x) * t,
            start.y + (end.y - start.y) * t)
    }

    /** Sutherland–Hodgman clipping, intersection relative to smaller polygon. */
    fun overlapFraction(first: List<OcrPoint>, second: List<OcrPoint>): Float {
        if (first.size != 4 || second.size != 4) return 0f
        val sign = if (signedArea(second) >= 0f) 1f else -1f
        var polygon = first
        for (j in second.indices) {
            val a = second[j]
            val b = second[(j + 1) % second.size]
            if (polygon.isEmpty()) return 0f
            val output = mutableListOf<OcrPoint>()
            for (i in polygon.indices) {
                val current = polygon[i]
                val previous = polygon[(i + polygon.size - 1) % polygon.size]
                val currInside = cross(a, b, current) * sign >= -.1f
                val prevInside = cross(a, b, previous) * sign >= -.1f
                if (currInside != prevInside) {
                    output += intersect(previous, current, a, b)
                }
                if (currInside) output += current
            }
            polygon = output
        }
        return polygonArea(polygon) /
            minOf(polygonArea(first), polygonArea(second)).coerceAtLeast(1f)
    }

    fun rectangle(left: Int, top: Int, right: Int, bottom: Int): List<OcrPoint> =
        listOf(OcrPoint(left.toFloat(), top.toFloat()),
            OcrPoint(right.toFloat(), top.toFloat()),
            OcrPoint(right.toFloat(), bottom.toFloat()),
            OcrPoint(left.toFloat(), bottom.toFloat()))
}
