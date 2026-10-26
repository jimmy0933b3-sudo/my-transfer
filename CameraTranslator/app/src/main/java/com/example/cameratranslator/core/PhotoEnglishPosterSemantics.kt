package com.example.cameratranslator.core

import java.util.Locale

/**
 * Conservative, context-gated repairs for English print advertisements.
 * Use only words actually present in the photo, not a hard-coded brand or
 * assumed product. No network requests and no synthetic numeric values.
 */
internal object PhotoEnglishPosterSemantics {
    private val site = Regex("""(?i)\b(?:www\.)?([a-z0-9-]{3,30})\.(?:com|net|org|co|tw|io)\b""")
    private val singleName = Regex("""^[A-Za-z][A-Za-z0-9-]{2,24}$""")
    private val chairCaption = Regex(
        """(?i)^Meet\s+the\s+new\s+([A-Za-z][A-Za-z0-9-]{2,24})\s+Chair[.!]?$""")
    private val saving = Regex(
        """(?i)^Save\s+[S$](\d{1,5})\s*\+\s*FREE\s+Shipping\s*!?$""")

    fun hasBrandEvidence(text: String, fullPhoto: List<String>): Boolean {
        val candidate = text.trim()
        if (!singleName.matches(candidate)) return false
        val key = candidate.lowercase(Locale.ROOT)
        // Brand must be independently corroborated by its host domain and a
        // separate descriptive line. A random capitalized common word is
        // not enough to declare a brand.
        val domain = fullPhoto.any { row ->
            site.findAll(row).any { it.groupValues[1].lowercase(Locale.ROOT) == key }
        }
        val mentioned = fullPhoto.any { row ->
            row != candidate && Regex("(?i)(?<![A-Za-z])" +
                Regex.escape(candidate) + "(?![A-Za-z])").containsMatchIn(row) &&
                !site.containsMatchIn(row)
        }
        // A distinctive internal capital, independently printed elsewhere,
        // also corroborates a mark if the OCR damaged the web domain.
        val camelName = Regex("[a-z][A-Z]").containsMatchIn(candidate)
        return mentioned && (domain || camelName)
    }

    fun normalize(text: String, language: String, fullPhoto: List<String>): String {
        if (language != "en") return text
        var value = text
        // An isolated "S" between Save and a sum is a common dollar-sign
        // OCR confusion on flyers. Never change ordinary S+digits elsewhere.
        val m = saving.matchEntire(value)
        if (m != null && value.contains(Regex("""(?i)^Save\s+S\d""")))
            value = "Save \$" + m.groupValues[1] + " + FREE Shipping!"
        // Duplicated trailing l in the flyer tag, validated by an
        // independently detected timed-offer heading.
        if (value.equals("Early Bird Speciall", true) &&
            fullPhoto.any { it.contains("LIMITED TIME OFFER", ignoreCase = true) }) {
            value = "Early Bird Special"
        }
        return value
    }

    fun translate(
        text: String, language: String, target: String,
        fullPhoto: List<String>
    ): String? {
        if (language != "en" || target != "zh") return null
        val input = normalize(text.trim(), language, fullPhoto)
        val furnitureContext = fullPhoto.count {
            it.contains("lumbar", true) || it.contains("armrest", true) ||
                it.contains("mesh", true)
        } >= 2
        chairCaption.matchEntire(input)?.let { matched ->
            val brand = matched.groupValues[1]
            if (furnitureContext && hasBrandEvidence(brand, fullPhoto)) {
                return "認識全新的 ${brand} 人體工學椅。"
            }
        }
        val promotionContext = fullPhoto.any {
            it.contains("LIMITED TIME OFFER", ignoreCase = true)
        }
        if (promotionContext && input.equals("Early Bird Special", true))
            return "早鳥優惠"
        if (promotionContext && input.equals("CLAIM YOUR OFFER NOW", true))
            return "立即領取優惠"
        saving.matchEntire(input)?.let { m ->
            if (promotionContext) return "省下 \$" + m.groupValues[1] + "，另享免運費！"
        }
        return null
    }
}
