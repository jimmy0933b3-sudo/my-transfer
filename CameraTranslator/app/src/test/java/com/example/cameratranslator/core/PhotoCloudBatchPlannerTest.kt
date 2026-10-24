package com.example.cameratranslator.core

import org.junit.Assert.*
import org.junit.Test

class PhotoCloudBatchPlannerTest {
    @Test
    fun groupsDoNotMixSourceLanguagesOrLosePhotoIndexes() {
        val languages = listOf("ja", "en", "ja", "ja", "en", "ja", "en", "ja")
        val result = PhotoCloudBatchPlanner.plan(languages.indices.toList()) {
            languages[it]
        }
        assertEquals(listOf(listOf(0, 2, 3, 5, 7), listOf(1, 4, 6)), result)
        assertEquals(languages.indices.toList(), result.flatten().sorted())
        result.forEach { batch ->
            assertEquals(1, batch.map { languages[it] }.distinct().size)
        }
    }

    @Test
    fun capsEachBatchAtSixAndPreservesItsInternalOrdering() {
        val result = PhotoCloudBatchPlanner.plan((0 until 16).toList()) { "ja" }
        assertEquals(listOf(6, 6, 4), result.map { it.size })
        assertEquals((0 until 16).toList(), result.flatten())
    }

    @Test
    fun emptyInputProducesNoCloudCalls() {
        assertTrue(PhotoCloudBatchPlanner.plan(emptyList()) { "ja" }.isEmpty())
    }
}
