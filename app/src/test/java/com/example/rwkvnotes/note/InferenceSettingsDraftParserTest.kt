package com.example.rwkvnotes.note

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class InferenceSettingsDraftParserTest {
    @Test
    fun parseInferenceSettingsDraft_parsesValidNumbers() {
        val parsed = parseInferenceSettingsDraft(
            maxTokensText = "256",
            temperatureText = "0.7",
            topPText = "0.9",
        )

        assertEquals(256, parsed.maxTokens)
        assertEquals(0.7, parsed.temperature, 0.0)
        assertEquals(0.9, parsed.topP, 0.0)
    }

    @Test
    fun parseInferenceSettingsDraft_rejectsInvalidTopP() {
        try {
            parseInferenceSettingsDraft(
                maxTokensText = "256",
                temperatureText = "0.7",
                topPText = "1.5",
            )
            fail("expected parse failure")
        } catch (expected: IllegalArgumentException) {
            assertEquals("topP must be in [0,1]", expected.message)
        }
    }
}
