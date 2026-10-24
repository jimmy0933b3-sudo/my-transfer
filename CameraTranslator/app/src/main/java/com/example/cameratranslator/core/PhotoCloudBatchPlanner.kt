package com.example.cameratranslator.core

/**
 * Batch Azure OCR translation without combining individual text strings.
 *
 * Every batch contains only one source language, preserving the original OCR
 * block indices to map translations back onto the correct photo rectangles.
 */
internal object PhotoCloudBatchPlanner {
    fun plan(
        eligibleIndices: List<Int>,
        languageFor: (Int) -> String
    ): List<List<Int>> =
        eligibleIndices.groupBy(languageFor)
            .values.flatMap { group -> group.chunked(6) }
}
