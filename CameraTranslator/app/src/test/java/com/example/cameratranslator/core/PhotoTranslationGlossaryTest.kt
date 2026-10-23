package com.example.cameratranslator.core

import org.junit.Assert.*
import org.junit.Test

class PhotoTranslationGlossaryTest {
    @Test fun educationalBookTitleIsNotTransliterated() {
        assertEquals("遊戲手冊", PhotoTranslationGlossary.builtin("Playbook", "en", "zh"))
        assertEquals("遊戲手冊", PhotoTranslationGlossary.builtin("PLAYBOOK", "en", "zh"))
        assertEquals("遊戲手冊", PhotoTranslationGlossary.builtin("  Playbook  ", "ja", "zh"))
    }

    @Test fun glossaryDoesNotReplacePartsOfSentences() {
        assertNull(PhotoTranslationGlossary.builtin("Read the playbook carefully", "en", "zh"))
        assertNull(PhotoTranslationGlossary.builtin("Playbook", "en", "ja"))
        assertNull(PhotoTranslationGlossary.builtin("Playbook", "en", "en"))
        assertNull(PhotoTranslationGlossary.builtin("Playbooks", "en", "zh"))
    }

    @Test fun userCorrectionKeyIsStableAcrossCase() {
        assertEquals(
            PhotoTranslationGlossary.key("Playbook", "en", "zh"),
            PhotoTranslationGlossary.key("  PLAYBOOK  ", "en", "zh")
        )
        assertNotEquals(
            PhotoTranslationGlossary.key("Playbook", "en", "zh"),
            PhotoTranslationGlossary.key("Playbook", "en", "ja")
        )
        assertFalse(PhotoTranslationGlossary.isValidCorrection("Playbook", ""))
    }
}
