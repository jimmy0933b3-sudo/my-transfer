package com.example.cameratranslator.core

import org.junit.Assert.*
import org.junit.Test

class SnapshotTextPolicyTest {
    @Test fun photoLabelsSupportAllRequestedLanguages() {
        assertEquals("英文", SnapshotTextPolicy.displayLanguage("en"))
        assertEquals("日文", SnapshotTextPolicy.displayLanguage("ja"))
        assertEquals("中文", SnapshotTextPolicy.displayLanguage("zh"))
        assertEquals("泰文", SnapshotTextPolicy.displayLanguage("th"))
    }

    @Test fun japanesePackagingRetainsEnglishBrands() {
        assertTrue(SnapshotTextPolicy.isLatinBrand("TAKARA TOMY"))
        assertTrue(SnapshotTextPolicy.isLatinBrand("BENI BEAR"))
        assertFalse(SnapshotTextPolicy.isLatinBrand("We can see a horse"))
        assertFalse(SnapshotTextPolicy.isLatinBrand("ダイキャストビークル"))
        assertEquals("en", SnapshotTextPolicy.sourceForText("TAKARA TOMY", "ja"))
        assertEquals("ja", SnapshotTextPolicy.sourceForText("全部で10種類！", "ja"))
    }

    @Test fun thaiRequiresGenuineThaiScript() {
        assertTrue(SnapshotTextPolicy.containsThai("สวัสดี"))
        assertFalse(SnapshotTextPolicy.containsThai("TAKARA TOMY"))
        assertFalse(SnapshotTextPolicy.containsThai("邦尼熊"))
    }
}
