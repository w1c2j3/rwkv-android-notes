package com.example.rwkvnotes.ai.protocol

import kotlinx.serialization.Serializable

@Serializable
data class InferenceRequestJson(
    val prompt: String,
    val modelPath: String,
    val maxTokens: Int,
    val temperature: Double,
    val topP: Double,
    val stream: Boolean = true,
)

@Serializable
data class TokenChunkJson(
    val index: Int,
    val token: String,
    val isFinished: Boolean = false,
)

@Serializable
data class InferenceFinalJson(
    val markdown: String,
    val tags: List<String>,
    val raw: String,
)

@Serializable
data class ErrorJson(
    val code: String,
    val message: String,
)

@Serializable
data class NativeFinalEnvelopeJson(
    val ok: Boolean,
    val result: InferenceFinalJson? = null,
    val error: ErrorJson? = null,
)

sealed interface InferenceEvent {
    data class Token(val chunk: TokenChunkJson) : InferenceEvent
    data class Completed(val result: InferenceFinalJson) : InferenceEvent
    data class Failed(val error: ErrorJson) : InferenceEvent
}
