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
    private val monthNames = listOf(
        "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน",
        "พฤษภาคม", "มิถุนายน", "กรกฎาคม", "สิงหาคม",
        "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม"
    )
    private val calendarDate = Regex(
        """^([0-9๐-๙]{1,2})\s*[-–—~]\s*([0-9๐-๙]{1,2})\s+(.+)$"""
    )
    private fun thaiInteger(value: String): Int? =
        value.map { digit ->
            when (digit) {
                in '๐'..'๙' -> ('0'.code + digit.code - '๐'.code).toChar()
                else -> digit
            }
        }.joinToString("").toIntOrNull()

    /**
     * Remove one misread Thai glyph BETWEEN two clearly visible Arabic date
     * digits, only when a known Thai month follows and the result is a real
     * increasing day range. 13-1ไ5 เมษายน -> 13-15 เมษายน.
     * Do not guess missing digits, arbitrary months or nearby captions.
     */
    fun normalizeCalendarLabel(text: String): String {
        val value = text.trim()
        if (calendarDate.matches(value)) return value
        val corrupted = Regex(
            """^([0-9]{1,2})\s*[-–—~]\s*([0-9])([\u0e00-\u0e7f])([0-9])\s+(.+)$"""
        ).matchEntire(value) ?: return value
        val first = corrupted.groupValues[1].toIntOrNull() ?: return value
        val last = (corrupted.groupValues[2] + corrupted.groupValues[4]).toIntOrNull()
            ?: return value
        val month = corrupted.groupValues[5].trim()
        if (first !in 1..31 || last !in first..31 || month !in monthNames) return value
        return "${first}-${last} ${month}"
    }

    /**
     * A verified Thai calendar range can be translated without allowing
     * a cloud model to invent a different month or day.
     */
    fun translateCalendarLabel(text: String): String? {
        val match = calendarDate.matchEntire(normalizeCalendarLabel(text)) ?: return null
        val first = thaiInteger(match.groupValues[1]) ?: return null
        val last = thaiInteger(match.groupValues[2]) ?: return null
        val month = monthNames.indexOf(match.groupValues[3].trim()) + 1
        if (month == 0 || first !in 1..31 || last !in first..31) return null
        return "${month}月${first}日至${last}日"
    }

    /** A multi-line Tesseract rectangle must not be presented as one line. */
    fun plausibleLineGeometry(line: Line, imageWidth: Int): Boolean =
        line.width >= 10 && line.height >= 10 && imageWidth > 0 &&
            line.height <= max(50f, imageWidth * .22f)

    // Tesseract confidence alone is not a language-quality score. In the
    // reported Songkran poster, malformed Thai scored .90 while readable
    // ประเทศไทย scored .84. Reject glyph soup before it is translated.
    private val repeatedThaiMarks = Regex("[\\u0e31\\u0e34-\\u0e3a\\u0e47-\\u0e4e]{3,}")
    private fun noisyThai(text: String): Boolean {
        if (repeatedThaiMarks.containsMatchIn(text) ||
            Regex("""\s{5,}""").containsMatchIn(text)) return true
        val marks = text.count { it == '\u0e31' || it in '\u0e34'..'\u0e3a' ||
            it in '\u0e47'..'\u0e4e' }
        val consonants = text.count { it in '\u0e01'..'\u0e2e' }
        if (marks >= 6 && marks > consonants) return true
        if (text.count { it in "|[]{}<>" } >= 2) return true
        return false
    }

    private fun quality(line: Line): Float {
        val punctuation = line.text.count { it in "|[]{}<>" }
        val startsWithOddMark = line.text.trimStart().firstOrNull()?.let {
            it in '\u0e47'..'\u0e4e' || it == 'ๆ'
        } == true
        return line.confidence - punctuation * .14f -
            (if (startsWithOddMark) .14f else 0f) -
            (if (noisyThai(line.text)) .5f else 0f)
    }

    /** Compare independent recognition models without duplicating boxes. */
    fun combineIndependent(
        tesseract: List<Line>, paddle: List<Line>
    ): List<Line> {
        val result = tesseract.filter { plausible(it.text) }.toMutableList()
        var newlyFound = 0
        var replaced = 0
        for (candidate in paddle.filter { plausible(it.text) && it.confidence >= .55f }) {
            val match = result.indexOfFirst {
                overlap(it, candidate) >= .60f &&
                    (sameVisualLine(it, candidate) || closeText(it.text, candidate.text) ||
                        samePrintedLine(it, candidate))
            }
            if (match >= 0) {
                // Paddle may decode a single printed Thai line much better
                // than Tesseract even when their strings do not resemble
                // each other. The physical footprint provides an independent
                // crosscheck, but require a material confidence improvement
                // before overriding an otherwise different string.
                val old = result[match]
                val footprintOnly = samePrintedLine(old, candidate) &&
                    !sameVisualLine(old, candidate) &&
                    !closeText(old.text, candidate.text)
                val shouldReplace = if (footprintOnly) {
                    candidate.confidence >= .80f &&
                        candidate.confidence >= old.confidence + .045f &&
                        quality(candidate) >= quality(old) + .035f
                } else quality(candidate) >= quality(old) - .06f
                if (shouldReplace) {
                    result[match] = candidate
                    replaced++
                }
            } else if (result.none { overlap(it, candidate) >= .75f }) {
                result.add(candidate)
                newlyFound++
            }
        }
        com.example.cameratranslator.core.DiagnosticLog.record(
            "THAI_CROSSCHECK", "Tesseract=" + tesseract.size +
                "；Paddle=" + paddle.size + "；採用Paddle=" + replaced +
                "；補充文字=" + newlyFound + "；輸出=" + result.size
        )
        return result.sortedWith(compareBy<Line> { it.top }.thenBy { it.left })
    }

    private val latinUrl = Regex(
        """(?i)(?:https?://|(?:[a-z0-9-]+\.)+(?:com|net|org|co|tw|io|app)(?:/|$))"""
    )

    fun isDateRange(text: String): Boolean = range.matches(text.trim())
    fun isCalendarLabel(text: String): Boolean =
        translateCalendarLabel(text) != null

    fun isBrowserUrl(text: String): Boolean = latinUrl.containsMatchIn(text.trim())

    /** Reject Thai-script hallucinations made mostly of punctuation or combining marks. */
    fun plausible(text: String): Boolean {
        if (isDateRange(text)) return true
        if (!ThaiModelIntegrity.isThaiLine(text)) return false
        val consonants = text.count { it in '\u0e01'..'\u0e2e' }
        val thai = text.count { it in '\u0e01'..'\u0e5b' }
        val letters = text.count(Char::isLetter)
        return consonants >= 2 && thai >= 3 && thai * 2 >= letters &&
            !noisyThai(text)
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

    /**
     * The same Thai printed headline can have very different strings in AUTO
     * and SPARSE (diacritics and character segmentation). Matching literal
     * OCR text alone leaves duplicated overlapping translations.
     *
     * Require near containment AND an identical baseline/left edge so two
     * genuinely separate adjacent lines are never collapsed by geometry.
     */
    /**
     * Independent engines can read exactly the same printed Thai word with
     * completely different glyph strings. OCR boxes can also have different
     * vertical padding: Tesseract's [337,859]-[497,876] is 17 px tall
     * while Paddle's [332,848]-[500,885] is 37 px tall.
     *
     * Compare the actual centers, horizontal footprint and containment of
     * the smaller rectangle rather than requiring matching top edges.
     * This must NOT treat adjacent columns or neighboring baselines as one.
     */
    private fun samePrintedLine(a: Line, b: Line): Boolean {
        val aWidth = a.width.coerceAtLeast(1)
        val bWidth = b.width.coerceAtLeast(1)
        val aHeight = a.height.coerceAtLeast(1)
        val bHeight = b.height.coerceAtLeast(1)
        val narrower = min(aWidth, bWidth)
        val taller = max(aHeight, bHeight)
        val shorter = min(aHeight, bHeight)
        val firstLength = normalized(a.text).length
        val secondLength = normalized(b.text).length
        return overlap(a, b) >= .82f &&
            narrower >= max(aWidth, bWidth) * .74f &&
            shorter >= taller * .35f &&
            kotlin.math.abs((a.left + a.right) - (b.left + b.right)) <=
                narrower * .20f &&
            kotlin.math.abs((a.top + a.bottom) - (b.top + b.bottom)) <=
                taller * .44f &&
            min(firstLength, secondLength) >= 5 &&
            min(firstLength, secondLength) >= max(firstLength, secondLength) * .72f
    }

    private fun sameVisualLine(a: Line, b: Line): Boolean {
        val minHeight = min(a.height, b.height).coerceAtLeast(1)
        val minWidth = min(a.width, b.width).coerceAtLeast(1)
        val longer = max(normalized(a.text).length, normalized(b.text).length)
        val shorter = min(normalized(a.text).length, normalized(b.text).length)
        return overlap(a, b) >= .88f &&
            kotlin.math.abs(a.top - b.top) <= minHeight * .23f &&
            kotlin.math.abs(a.left - b.left) <= minWidth * .12f &&
            kotlin.math.abs((a.top + a.bottom) - (b.top + b.bottom)) <=
                max(a.height, b.height) * .42f &&
            shorter >= 4 && longer <= shorter * 1.45f
    }

    data class RecoveryRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * Re-scan ONLY plausible missing headline/date bands, not arbitrary
     * poster areas or the browser toolbar. Regions are image-coordinate based.
     */
    fun recoveryRegions(lines: List<Line>, width: Int, height: Int): List<RecoveryRegion> {
        if (lines.isEmpty() || width < 120 || height < 200) return emptyList()
        val ordered = lines.sortedWith(compareBy<Line> { it.top }.thenBy { it.left })
        val regions = mutableListOf<RecoveryRegion>()
        for (i in 0 until ordered.lastIndex) {
            val upper = ordered[i]
            val lower = ordered[i + 1]
            val gap = lower.top - upper.bottom
            val minHeight = min(upper.height, lower.height).coerceAtLeast(1)
            val sharedX = min(upper.right, lower.right) - max(upper.left, lower.left)
            if (gap < max(20, minHeight / 4) ||
                gap > max(160, minHeight * 2) ||
                sharedX < min(upper.width, lower.width) * .3f) continue
            val top = (upper.bottom + 4).coerceIn(0, height)
            val bottom = (lower.top - 4).coerceIn(0, height)
            if (bottom - top >= 20) {
                regions.add(RecoveryRegion(0, top, width, bottom))
            }
        }
        // A festival poster often has its date ribbon beneath the last
        // recognized line. Search a short tail, not the rest of the image.
        val last = ordered.last()
        val tailTop = (last.bottom + 6).coerceIn(0, height)
        val tailBottom = (last.bottom + max(145, last.height * 2)).coerceIn(0, height)
        if (tailBottom - tailTop >= 28) {
            regions.add(RecoveryRegion(0, tailTop, width, tailBottom))
        }
        return regions.take(4)
    }

    /** Latin OCR knows Arabic digits that a Thai-only model may miss. */
    fun attachLatinDateRanges(
        thaiLines: List<Line>, latinLines: List<Line>
    ): List<Line> {
        val result = thaiLines.toMutableList()
        for (latin in latinLines.filter { isDateRange(it.text) && it.confidence >= .5f }) {
            val match = result.indexOfFirst { item ->
                month.matches(item.text.trim()) &&
                    min(item.bottom, latin.bottom) - max(item.top, latin.top) >=
                        min(item.height, latin.height) * .4f &&
                    item.left >= latin.left &&
                    item.left - latin.right in -8..max(55, latin.height * 2)
            }
            if (match != -1) {
                val m = result[match]
                result[match] = Line(
                    latin.text.trim() + " " + m.text.trim(),
                    min(latin.left, m.left), min(latin.top, m.top),
                    max(latin.right, m.right), max(latin.bottom, m.bottom),
                    min(latin.confidence, m.confidence)
                )
            }
        }
        return result
    }

    /** Agreement between page-segmentation modes can retain medium-confidence text. */
    fun corroborated(candidate: Line, primary: List<Line>, sparse: List<Line>): Boolean =
        primary.any { overlap(candidate, it) >= .60f &&
            closeText(candidate.text, it.text) } &&
        sparse.any { overlap(candidate, it) >= .60f &&
            closeText(candidate.text, it.text) }

    fun merge(primary: List<Line>, sparse: List<Line>): List<Line> {
        val kept = mutableListOf<Line>()
        for (item in (primary + sparse).filter { plausible(it.text) }) {
            val match = kept.indexOfFirst { other ->
                overlap(item, other) >= .68f &&
                    (closeText(item.text, other.text) || sameVisualLine(item, other))
            }
            if (match == -1) kept.add(item)
            else if (quality(item) > quality(kept[match]) + .035f ||
                (quality(item) >= quality(kept[match]) - .04f &&
                    item.text.length > kept[match].text.length + 2 &&
                    !noisyThai(item.text))) kept[match] = item
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
                        gap >= -height * .20f && gap <= max(28f, height * 1.3f)
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
