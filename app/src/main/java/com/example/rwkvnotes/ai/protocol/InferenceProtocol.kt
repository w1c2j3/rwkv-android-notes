package com.example.rwkvnotes.ai.protocol

import kotlinx.serialization.Serializable

@Serializable
data class InferenceRequestJson(
    val prompt: String,
    val modelPath: String,
    val maxTokens: Int,
    val temperature: Double,
    val topP: Double,
    val topK: Int = 40,
    val repeatPenalty: Double = 1.10,
    val stream: Boolean = true,
)

fun InferenceRequestJson.validateOrError(): ErrorJson? {
    if (prompt.isBlank()) return ErrorJson("REQUEST_PROMPT_MISSING", "prompt is blank")
    if (modelPath.isBlank()) return ErrorJson("MODEL_PATH_EMPTY", "model path is blank")
    if (maxTokens <= 0) return ErrorJson("REQUEST_MAX_TOKENS_INVALID", "maxTokens must be > 0")
    if (temperature < 0.0) return ErrorJson("REQUEST_TEMPERATURE_INVALID", "temperature must be >= 0")
    if (topP < 0.0 || topP > 1.0) return ErrorJson("REQUEST_TOP_P_INVALID", "topP must be in [0,1]")
    if (topK <= 0) return ErrorJson("REQUEST_TOP_K_INVALID", "topK must be > 0")
    if (repeatPenalty < 1.0) return ErrorJson("REQUEST_REPEAT_PENALTY_INVALID", "repeatPenalty must be >= 1.0")
    return null
}

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

fun normalizeNativeError(error: ErrorJson): ErrorJson {
    val known = setOf(
        "ENGINE_INIT_FAILED",
        "MODEL_PATH_EMPTY",
        "MODEL_OPEN_FAILED",
        "MODEL_STAT_FAILED",
        "MODEL_MMAP_FAILED",
        "UNSUPPORTED_MODEL_FORMAT_PTH",
        "REQUEST_JSON_INVALID",
        "REQUEST_MAX_TOKENS_INVALID",
        "REQUEST_TEMPERATURE_INVALID",
        "REQUEST_TOP_P_INVALID",
        "REQUEST_TOP_K_INVALID",
        "REQUEST_REPEAT_PENALTY_INVALID",
        "MODEL_NOT_LOADED",
        "TOKENIZER_VOCAB_OPEN_FAILED",
        "TOKENIZER_VOCAB_PARSE_FAILED",
        "INFER_UNAVAILABLE",
        "INFER_STEP_FAILED",
        "INFER_STEP_FAILED_PREFILL",
        "SAMPLER_INVALID_LOGITS",
        "TOKENIZER_INPUT_EMPTY",
        "TOKENIZER_TOKEN_OUT_OF_RANGE",
        "INFER_CANCELLED",
        "ENGINE_HANDLE_INVALID",
        "ENGINE_SHUTDOWN",
        "REAL_RWKV_SOURCE_MISSING",
        "REAL_RWKV_LOAD_MODEL_FAILED",
        "REAL_RWKV_MODEL_INVALID",
        "REAL_RWKV_EVAL_FAILED",
    )
    return if (error.code in known) error else ErrorJson("NATIVE_UNKNOWN_ERROR", error.message)
}

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
