package com.example.cameratranslator.core

import org.junit.Assert.*
import org.junit.Test

class PhotoRenderSizingTest {
    @Test fun highResPhotoUsesActualPixelHeight() {
        // A photographed 250px-tall title must NOT be stuck at 70px.
        assertTrue(PhotoRenderSizing.initialFontSize(250f) > 180f)
        assertEquals(22f, PhotoRenderSizing.initialFontSize(25f), 0.01f)
        assertTrue(PhotoRenderSizing.initialFontSize(600f) <= 320f)
        assertTrue(PhotoRenderSizing.decrement(180f) > 1f)
    }

    @Test fun textCanWrapMoreRowsWithoutLosingReadability() {
        assertEquals(4, PhotoRenderSizing.lineLimit("Playbook"))
        assertEquals(4, PhotoRenderSizing.lineLimit("BENI BEAR"))
        assertEquals(7, PhotoRenderSizing.lineLimit("Long paragraph about trees\nand 30 types of animals"))
    }
}
