package com.example.rwkvnotes.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelImportFileNamingTest {
    @Test
    fun normalizeImportedModelFileName_keepsConfiguredRuntimeExtension() {
        assertEquals("notes-runtime.bin", normalizeImportedModelFileName("notes-runtime.BIN", ".bin"))
    }

    @Test
    fun normalizeImportedModelFileName_replacesExistingExtension() {
        assertEquals("notes-runtime.bin", normalizeImportedModelFileName("notes-runtime.gguf", ".bin"))
    }

    @Test
    fun normalizeImportedModelFileName_sanitizesWhitespaceAndDriveSeparators() {
        assertEquals("rwkv_notes.bin", normalizeImportedModelFileName("E:\\models\\rwkv notes.bin", ".bin"))
    }

    @Test
    fun normalizeImportedModelFileName_usesFallbackWhenNameMissing() {
        assertEquals("imported-model.bin", normalizeImportedModelFileName(null, ".bin"))
    }
}
