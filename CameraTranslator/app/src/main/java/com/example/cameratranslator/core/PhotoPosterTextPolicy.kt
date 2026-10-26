package com.example.cameratranslator.core

/**
 * Photo-only editorial text protection. These rules prevent changing a
 * foreground graphic or pseudo-Latin template copy into an invented Chinese
 * translation. They do not modify camera OCR models or user corrections.
 */
internal object PhotoPosterTextPolicy {
    data class Block(
        val text: String,
        val language: String,
        val box: PhotoTextBoxLayout.Box?
    )

    // Only correct the very specific "1l AM" / "1I PM" OCR confusion.
    // Do not rewrite numbers in brand names, prose, URLs or other times.
    private val elevenAmPm = Regex("""(?i)(?<![a-z0-9])1[li](?=\s*(?:am|pm)\b)""")
    fun normalizeEnglishTime(text: String, language: String): String =
        if (language == "en") elevenAmPm.replace(text, "11") else text

    private val placeholderLead = Regex(
        """(?i)^(?:lorem\s+ipsum\b|consectetuer\b|consectetur\b|sed\s+diam\b|ismod\b|eiusmod\b|elit\b)"""
    )
    private val url = Regex("""(?i)^(?:https?://|www\.)\S+$""")

    fun preserveLiteral(text: String, language: String, photoText: List<String>): Boolean {
        if (language != "en") return false
        val value = text.trim()
        if (url.matches(value)) return true
        // Suppress only the familiar mockup placeholder, and only when the
        // same photograph independently contains the unambiguous opening.
        return photoText.any {
            it.trim().startsWith("Lorem ipsum", ignoreCase = true)
        } && placeholderLead.containsMatchIn(value)
    }

    private val shortHeadline = Regex("""[A-Za-z]{4,}(?:\s+[A-Za-z]{4,})?""")
    private fun isGraphicTitle(block: Block): Boolean =
        block.language == "en" && shortHeadline.matches(block.text.trim()) &&
            (block.box?.valid == true) && block.box.height >= 50

    /**
     * Stylized print can deliberately overlay two headline layers. Bounding
     * box inpainting on either one erases the other, even if their individual
     * OCR words are correct. Leave BOTH original foreground layers intact.
     *
     * Deliberately limited to tall, overlapping, short English display
     * titles; normal paragraph rows and neighboring labels are unaffected.
     */
    fun protectedOverprintIndices(blocks: List<Block>): Set<Int> {
        val protected = mutableSetOf<Int>()
        for (i in blocks.indices) for (j in i + 1 until blocks.size) {
            val a = blocks[i]
            val b = blocks[j]
            if (!isGraphicTitle(a) || !isGraphicTitle(b)) continue
            val first = a.box ?: continue
            val second = b.box ?: continue
            if (maxOf(first.height, second.height) < 90) continue
            val w = (minOf(first.right, second.right) -
                maxOf(first.left, second.left)).coerceAtLeast(0)
            val h = (minOf(first.bottom, second.bottom) -
                maxOf(first.top, second.top)).coerceAtLeast(0)
            val smaller = minOf(
                first.width.toLong() * first.height,
                second.width.toLong() * second.height
            ).coerceAtLeast(1)
            if ((w.toLong() * h) / smaller.toFloat() >= 0.30f) {
                protected.add(i)
                protected.add(j)
            }
        }
        return protected
    }
}
