package com.example.cameratranslator.ocr

import kotlin.math.max
import kotlin.math.min

/**
 * Conservative, pure-Kotlin combination of two Tesseract page segmentation
 * passes. The phone stays offline, and no OCR text is stored by this policy.
 *
 * Unlike choosing only PSM_AUTO, the sparse pass can find small isolated
 * photo captions and date ribbons even if the main paragraph pass succeeded.
 */
internal object ThaiPhotoLinePolicy {
    data class Line(
        val text: String, val left: Int, val top: Int,
        val right: Int, val bottom: Int, val confidence: Float
    ) {
        val width get() = right - left
        val height get() = bottom - top
    }
    private val range = Regex("""^[0-9๐-๙]{1,2}\s*[-–—~]\s*[0-9๐-๙]{1,2}$""")
    private val month = Regex(
        """^(?:มกราคม|กุมภาพันธ์|มีนาคม|เมษายน|พฤษภาคม|มิถุนายน|กรกฎาคม|สิงหาคม|กันยายน|ตุลาคม|พฤศจิกายน|ธันวาคม)$"""
    )
    private val latinUrl = Regex(
        """(?i)(?:https?://|(?:[a-z0-9-]+\.)+(?:com|net|org|co|tw|io|app)(?:/|$))"""
    )

    fun isDateRange(text: String): Boolean = range.matches(text.trim())
    fun isBrowserUrl(text: String): Boolean = latinUrl.containsMatchIn(text.trim())

    /** Reject Thai-script hallucinations made mostly of punctuation or combining marks. */
    fun plausible(text: String): Boolean {
        if (isDateRange(text)) return true
        if (!ThaiModelIntegrity.isThaiLine(text)) return false
        val consonants = text.count { it in '\u0e01'..'\u0e2e' }
        val thai = text.count { it in '\u0e01'..'\u0e5b' }
        val letters = text.count(Char::isLetter)
        return consonants >= 2 && thai >= 3 && thai * 2 >= letters
    }

    private fun intersection(a: Line, b: Line): Long =
        (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0).toLong() *
            (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0)

    /** Intersection relative to smaller box, not IoU, for jittery OCR rectangles. */
    private fun overlap(a: Line, b: Line): Float {
        val smaller = min(
            a.width.coerceAtLeast(1).toLong() * a.height.coerceAtLeast(1),
            b.width.coerceAtLeast(1).toLong() * b.height.coerceAtLeast(1)
        )
        return intersection(a, b) / smaller.toFloat()
    }

    private fun normalized(text: String): String =
        text.filterNot { it.isWhitespace() || it in ".,!?()（）、。-" }

    private fun closeText(a: String, b: String): Boolean {
        val first = normalized(a)
        val second = normalized(b)
        if (first == second) return true
        if (min(first.length, second.length) < 4) return false
        if (max(first.length, second.length) > min(first.length, second.length) * 1.5f)
            return false
        val prev = IntArray(second.length + 1) { it }
        val row = IntArray(second.length + 1)
        for (i in first.indices) {
            row[0] = i + 1
            for (j in second.indices) {
                row[j + 1] = min(
                    min(row[j] + 1, prev[j + 1] + 1),
                    prev[j] + if (first[i] == second[j]) 0 else 1
                )
            }
            row.copyInto(prev)
        }
        return 1f - prev[second.length] / max(first.length, second.length).toFloat() >= .68f
    }

    fun merge(primary: List<Line>, sparse: List<Line>): List<Line> {
        val kept = mutableListOf<Line>()
        for (item in (primary + sparse).filter { plausible(it.text) }) {
            val match = kept.indexOfFirst { other ->
                overlap(item, other) >= .68f && closeText(item.text, other.text)
            }
            if (match == -1) kept.add(item)
            else if (item.confidence > kept[match].confidence + .06f ||
                (item.confidence >= kept[match].confidence - .04f &&
                    item.text.length > kept[match].text.length + 2)) kept[match] = item
        }
        return joinDates(kept.sortedWith(compareBy<Line> { it.top }.thenBy { it.left }))
    }

    /** Join a date and its Thai month only when both share a baseline. */
    private fun joinDates(lines: List<Line>): List<Line> {
        val consumed = mutableSetOf<Int>()
        val result = mutableListOf<Line>()
        for ((index, item) in lines.withIndex()) {
            if (index in consumed) continue
            if (!isDateRange(item.text)) {
                result.add(item)
                continue
            }
            val monthIndex = lines.indices.filter { it != index && it !in consumed }
                .firstOrNull { i ->
                    val candidate = lines[i]
                    val height = min(item.height, candidate.height).coerceAtLeast(1)
                    val sharedY = min(item.bottom, candidate.bottom) -
                        max(item.top, candidate.top)
                    val gap = candidate.left - item.right
                    month.matches(candidate.text.trim()) &&
                        sharedY >= height * .55f &&
                        gap >= -height * .20f && gap <= max(28, height * 1.3f)
                }
            if (monthIndex == null) {
                result.add(item)
            } else {
                val next = lines[monthIndex]
                consumed.add(monthIndex)
                result.add(Line(item.text.trim() + " " + next.text.trim(),
                    min(item.left, next.left), min(item.top, next.top),
                    max(item.right, next.right), max(item.bottom, next.bottom),
                    min(item.confidence, next.confidence)))
            }
        }
        return result.sortedWith(compareBy<Line> { it.top }.thenBy { it.left })
    }

    /** URL evidence from the independent Latin reader vetoes only the same box. */
    fun masksThaiLine(thai: Line, latin: Line): Boolean =
        isBrowserUrl(latin.text) && overlap(thai, latin) >= .60f
}
