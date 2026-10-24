package com.example.cameratranslator.core

/** Remove common authentication secrets from copied diagnostic reports. */
internal object DiagnosticLogSanitizer {
    private val token = Regex(
        "(?i)\\b(authorization|api[_-]?key|access[_-]?token|password|secret|token)" +
            "\\s*[:=]\\s*([^\\s&,;]+)"
    )
    private val bearer = Regex("(?i)\\bBearer\\s+[a-z0-9._~+/-]+")
    private val query = Regex("(?i)([?&](?:key|token|api_key|access_token)=)[^&\\s]+")

    fun sanitize(message: String): String = message
        .replace(bearer, "Bearer [REDACTED]")
        .replace(token) { it.groupValues[1] + "=[REDACTED]" }
        .replace(query) { it.groupValues[1] + "[REDACTED]" }
}
