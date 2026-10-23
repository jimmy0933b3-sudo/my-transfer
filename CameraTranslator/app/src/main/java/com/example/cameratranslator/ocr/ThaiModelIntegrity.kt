package com.example.cameratranslator.ocr

import java.io.File
import java.security.MessageDigest

/**
 * Verify the exact upstream tessdata_fast tha.traineddata Git blob.
 * A plausible file size is NOT proof that an OCR model is valid.
 */
internal object ThaiModelIntegrity {
    const val BYTES = 1_072_600L
    const val GIT_BLOB_SHA1 = "ea28de3985668cac00d261c5b91f023ea66e19cd"

    fun verified(file: File): Boolean {
        if (!file.isFile || file.length() != BYTES) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update(("blob $BYTES\u0000").toByteArray(Charsets.UTF_8))
            file.inputStream().buffered().use { source ->
                val chunk = ByteArray(32768)
                while (true) {
                    val length = source.read(chunk)
                    if (length < 0) break
                    digest.update(chunk, 0, length)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) } == GIT_BLOB_SHA1
        } catch (_: Exception) {
            false
        }
    }

    fun isThaiLine(value: String): Boolean {
        val trimmed = value.trim()
        val thaiLetters = trimmed.count { it in '\u0E01'..'\u0E5B' }
        val otherLetters = trimmed.count { it.isLetter() } - thaiLetters
        return thaiLetters >= 2 && thaiLetters >= otherLetters
    }
}
