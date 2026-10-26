package com.example.cameratranslator.core

import java.text.Normalizer

/**
 * Compare only numbers which are supported by OCR text. The comparison is
 * advisory: matching digits alone never proves a translation correct.
 *
 * Chinese/Japanese numeral words with an explicit counting/date unit are
 * parsed, avoiding false rejections of 10種類 -> 十種, or 10月 -> 十月.
 * Digits embedded in suspected OCR Latin noise (Gi0, C10) are ignored.
 */
internal object PhotoNumericEvidence {
    private val arabic = Regex("(?<![A-Za-z0-9])\\d+(?:[.,]\\d+)*(?![A-Za-z0-9])")
    private val thousands = Regex(",(?=\\d{3}(?:\\D|$))")
    private val written = Regex("[零〇一二三四五六七八九十百千兩两]+(?=年|月|日|種|个|個|款|件|台|輛|次|份|頁|元|名|張|歲|週|周|盒|%|％)")
    private val thaiMonthNames = listOf(
        "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน",
        "พฤษภาคม", "มิถุนายน", "กรกฎาคม", "สิงหาคม",
        "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม"
    )
    // OCR may read an English date ("10 January") correctly while the
    // Chinese translation naturally spells out its month ("1月10日").
    // Require an adjacent day and a known month, never treat arbitrary
    // standalone words like "may" as calendar evidence.
    private val englishMonthPattern =
        "(?:january|february|march|april|may|june|july|august|september|sept?|october|november|december|jan|feb|mar|apr|jun|jul|aug|oct|nov|dec)"
    private val englishDayMonth = Regex(
        "(?i)\\b(?:[0-2]?\\d|3[01])\\s+(" + englishMonthPattern + ")\\b"
    )
    private val englishMonthDay = Regex(
        "(?i)\\b(" + englishMonthPattern + ")\\s+(?:[0-2]?\\d|3[01])\\b"
    )
    private fun monthNumber(name: String): String? =
        when (name.lowercase().take(3)) {
            "jan" -> "1"; "feb" -> "2"; "mar" -> "3"
            "apr" -> "4"; "may" -> "5"; "jun" -> "6"
            "jul" -> "7"; "aug" -> "8"; "sep" -> "9"
            "oct" -> "10"; "nov" -> "11"; "dec" -> "12"
            else -> null
        }

    private val digits = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '兩' to 2, '两' to 2,
        '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )
    private val units = mapOf('十' to 10, '百' to 100, '千' to 1000)

    private fun chineseNumber(value: String): String? {
        if (value.all { it in digits }) {
            return value.map { digits[it]!! }.joinToString("").toLongOrNull()?.toString()
        }
        var total = 0
        var current = 0
        for (char in value) {
            val digit = digits[char]
            val unit = units[char]
            when {
                digit != null -> current = digit
                unit != null -> {
                    total += (if (current == 0) 1 else current) * unit
                    current = 0
                }
                else -> return null
            }
        }
        return (total + current).toString()
    }

    private fun tokens(text: String, includeWritten: Boolean, includeThaiMonth: Boolean = false, includeEnglishMonth: Boolean = false): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .map { digit ->
                if (digit in '๐'..'๙') ('0'.code + digit.code - '๐'.code).toChar()
                else digit
            }.joinToString("")
        val result = arabic.findAll(normalized).map { match ->
            val raw = match.value.replace(thousands, "")
            if (raw.all(Char::isDigit)) raw.trimStart('0').ifEmpty { "0" } else raw
        }.toMutableList()
        if (includeWritten) {
            written.findAll(normalized).forEach { match ->
                chineseNumber(match.value)?.let(result::add)
            }
        }
        if (includeThaiMonth && result.isNotEmpty()) {
            // A Thai date names its month in Thai script rather than as an
            // Arabic numeral. Include that known month when verifying a
            // translated Chinese date such as 13-15 เมษายน -> 4月13日至15日.
            for ((index, name) in thaiMonthNames.withIndex()) {
                if (name in normalized) result.add((index + 1).toString())
            }
        }
        if (includeEnglishMonth && result.isNotEmpty()) {
            (englishDayMonth.findAll(normalized).map { it.groupValues[1] } +
                englishMonthDay.findAll(normalized).map { it.groupValues[1] })
                .forEach { monthNumber(it)?.let(result::add) }
        }
        return result.sorted()
    }

    /** True only if there is positive contradictory numeric evidence. */
    fun mismatched(source: String, translation: String): Boolean {
        val original = tokens(source, includeWritten = false, includeThaiMonth = true, includeEnglishMonth = true)
        if (original.isEmpty()) return false
        val target = tokens(translation, includeWritten = true)
        // No digit or explicit numeric word: fail open rather than erase an
        // otherwise useful translation. Other translation checks still apply.
        return target.isNotEmpty() && original != target
    }
}
