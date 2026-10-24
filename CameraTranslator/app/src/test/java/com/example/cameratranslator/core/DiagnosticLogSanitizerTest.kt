package com.example.cameratranslator.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogSanitizerTest {
    @Test fun protectsTokensInCopiedLogs() {
        val result = DiagnosticLogSanitizer.sanitize(
            "Authorization: abc123 Bearer abc.def api_key=SECRET123 " +
            "https://example.invalid/?token=hidden&hello=world"
        )
        for (secret in listOf("abc123", "abc.def", "SECRET123", "hidden")) {
            assertFalse(secret, result.contains(secret))
        }
        assertTrue(result.contains("[REDACTED]"))
    }
}
