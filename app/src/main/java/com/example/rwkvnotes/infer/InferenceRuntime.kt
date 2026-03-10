package com.example.rwkvnotes.infer

/**
 * Package boundary for the in-app inference module.
 *
 * Upper layers should only interact with this interface by providing config/request JSON and
 * receiving token/final JSON responses. They must not depend on vendored backend source layout.
 */
interface InferenceRuntime {
    fun initEngine(configJson: String): Long

    fun consumeLastErrorJson(): String?

    fun runInferenceStreamJson(
        engineHandle: Long,
        requestJson: String,
        callback: NativeTokenCallback,
    ): String

    fun cancelInference(engineHandle: Long)

    fun destroyEngine(engineHandle: Long)
}
