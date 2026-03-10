package com.example.rwkvnotes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFormatSupportTest {
    @Test
    fun normalizeRuntimeExtension_addsDotAndLowercases() {
        assertEquals(".bin", normalizeRuntimeExtension("BIN"))
        assertEquals(".gguf", normalizeRuntimeExtension(".GGUF"))
    }

    @Test
    fun matchesRuntimeExtension_acceptsConfiguredRuntimeFile() {
        assertTrue(matchesRuntimeExtension("rwkv-model.BIN", ".bin"))
        assertFalse(matchesRuntimeExtension("rwkv-model.pth", ".bin"))
    }
}
