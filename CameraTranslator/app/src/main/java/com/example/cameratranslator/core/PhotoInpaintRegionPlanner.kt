package com.example.cameratranslator.core

/**
 * Pure region planning. OCR boxes are expanded slightly so Telea can sample
 * source pixels around the text. Nearby boxes are grouped to inpaint from
 * the SAME original photo and avoid seams between overlapping repairs.
 */
internal object PhotoInpaintRegionPlanner {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val pixels get() = width.toLong() * height
        fun intersects(other: Bounds): Boolean =
            left <= other.right && other.left <= right &&
                top <= other.bottom && other.top <= bottom
        fun union(other: Bounds) = Bounds(
            minOf(left, other.left), minOf(top, other.top),
            maxOf(right, other.right), maxOf(bottom, other.bottom))
    }
    data class Region(val bounds: Bounds, val indices: List<Int>)
    data class Plan(val regions: List<Region>, val rejectedIndices: Set<Int>)

    fun plan(
        boxes: List<Bounds>, imageWidth: Int, imageHeight: Int,
        padding: Int = 9, maxRegionPixels: Long = 1_600_000L
    ): Plan {
        require(imageWidth > 0 && imageHeight > 0 && padding >= 0)
        val rejected = mutableSetOf<Int>()
        val groups = mutableListOf<Region>()
        boxes.forEachIndexed { index, box ->
            if (box.width <= 0 || box.height <= 0) {
                rejected += index
                return@forEachIndexed
            }
            val padded = Bounds(
                (box.left - padding).coerceIn(0, imageWidth),
                (box.top - padding).coerceIn(0, imageHeight),
                (box.right + padding).coerceIn(0, imageWidth),
                (box.bottom + padding).coerceIn(0, imageHeight))
            if (padded.width <= 0 || padded.height <= 0) {
                rejected += index
                return@forEachIndexed
            }
            var joined = Region(padded, listOf(index))
            var changed: Boolean
            do {
                changed = false
                val iterator = groups.iterator()
                while (iterator.hasNext()) {
                    val other = iterator.next()
                    if (joined.bounds.intersects(other.bounds)) {
                        joined = Region(joined.bounds.union(other.bounds),
                            joined.indices + other.indices)
                        iterator.remove()
                        changed = true
                    }
                }
            } while (changed)
            groups += joined
        }
        val safe = groups.filter { region ->
            if (region.bounds.pixels <= maxRegionPixels) true
            else {
                rejected.addAll(region.indices)
                false
            }
        }
        return Plan(safe.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left })),
            rejected)
    }
}
