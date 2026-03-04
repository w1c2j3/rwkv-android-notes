package com.example.rwkvnotes.ai

import com.example.rwkvnotes.ai.protocol.InferenceRequestJson
import com.example.rwkvnotes.ai.protocol.InferenceFinalJson
import com.example.rwkvnotes.ai.protocol.NativeFinalEnvelopeJson
import com.example.rwkvnotes.ai.protocol.ErrorJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceProtocolTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun requestJson_round_trip() {
        val request = InferenceRequestJson(
            prompt = "abc",
            modelPath = "/model.bin",
            maxTokens = 32,
            temperature = 0.8,
            topP = 0.95,
            stream = true,
        )

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<InferenceRequestJson>(encoded)

        assertEquals(request, decoded)
    }

    @Test
    fun finalEnvelope_success_round_trip() {
        val envelope = NativeFinalEnvelopeJson(
            ok = true,
            result = InferenceFinalJson(
                markdown = "## title",
                tags = listOf("a", "b"),
                raw = "raw",
            ),
        )
        val encoded = json.encodeToString(envelope)
        val decoded = json.decodeFromString<NativeFinalEnvelopeJson>(encoded)
        assertEquals(envelope, decoded)
    }

    @Test
    fun finalEnvelope_error_round_trip() {
        val envelope = NativeFinalEnvelopeJson(
            ok = false,
            error = ErrorJson("invalid_request_json", "request missing prompt"),
        )
        val encoded = json.encodeToString(envelope)
        val decoded = json.decodeFromString<NativeFinalEnvelopeJson>(encoded)
        assertTrue(!decoded.ok)
        assertEquals("invalid_request_json", decoded.error?.code)
    }
}
