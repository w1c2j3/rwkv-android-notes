package com.example.rwkvnotes.ai

import com.example.rwkvnotes.ai.protocol.ErrorJson
import com.example.rwkvnotes.ai.protocol.InferenceRequestJson
import com.example.rwkvnotes.ai.protocol.normalizeNativeError
import com.example.rwkvnotes.ai.protocol.validateOrError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InferenceValidationTest {
    @Test
    fun requestValidation_validRequest_returnsNull() {
        val req = InferenceRequestJson(
            prompt = "hello",
            modelPath = "/tmp/model.bin",
            maxTokens = 32,
            temperature = 0.7,
            topP = 0.9,
            stream = true,
        )
        assertNull(req.validateOrError())
    }

    @Test
    fun requestValidation_invalidTopP_returnsError() {
        val req = InferenceRequestJson(
            prompt = "hello",
            modelPath = "/tmp/model.bin",
            maxTokens = 32,
            temperature = 0.7,
            topP = 1.5,
            stream = true,
        )
        val err = req.validateOrError()
        assertEquals("REQUEST_TOP_P_INVALID", err?.code)
    }

    @Test
    fun requestValidation_invalidTopK_returnsError() {
        val req = InferenceRequestJson(
            prompt = "hello",
            modelPath = "/tmp/model.bin",
            maxTokens = 32,
            temperature = 0.7,
            topP = 0.9,
            topK = 0,
            stream = true,
        )
        val err = req.validateOrError()
        assertEquals("REQUEST_TOP_K_INVALID", err?.code)
    }

    @Test
    fun requestValidation_invalidRepeatPenalty_returnsError() {
        val req = InferenceRequestJson(
            prompt = "hello",
            modelPath = "/tmp/model.bin",
            maxTokens = 32,
            temperature = 0.7,
            topP = 0.9,
            topK = 16,
            repeatPenalty = 0.8,
            stream = true,
        )
        val err = req.validateOrError()
        assertEquals("REQUEST_REPEAT_PENALTY_INVALID", err?.code)
    }

    @Test
    fun normalizeNativeError_unknownCode_mapsToUnifiedCode() {
        val normalized = normalizeNativeError(ErrorJson("abc_xyz", "boom"))
        assertEquals("NATIVE_UNKNOWN_ERROR", normalized.code)
        assertEquals("boom", normalized.message)
    }

    @Test
    fun normalizeNativeError_knownCode_keepsCode() {
        val normalized = normalizeNativeError(ErrorJson("MODEL_NOT_LOADED", "no model"))
        assertEquals("MODEL_NOT_LOADED", normalized.code)
    }

    @Test
    fun normalizeNativeError_requestJsonInvalid_keepsCode() {
        val normalized = normalizeNativeError(ErrorJson("REQUEST_JSON_INVALID", "bad json"))
        assertEquals("REQUEST_JSON_INVALID", normalized.code)
    }

    @Test
    fun normalizeNativeError_engineShutdown_keepsCode() {
        val normalized = normalizeNativeError(ErrorJson("ENGINE_SHUTDOWN", "shutdown"))
        assertEquals("ENGINE_SHUTDOWN", normalized.code)
    }

    @Test
    fun normalizeNativeError_unsupportedPth_keepsCode() {
        val normalized = normalizeNativeError(ErrorJson("UNSUPPORTED_MODEL_FORMAT_PTH", "bad model"))
        assertEquals("UNSUPPORTED_MODEL_FORMAT_PTH", normalized.code)
    }
}
