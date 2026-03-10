package com.example.rwkvnotes.infer

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeRwkvBridge @Inject constructor() : InferenceRuntime {
    init {
        System.loadLibrary("rwkv_jni")
    }

    override fun initEngine(configJson: String): Long = nativeInitEngine(configJson)

    override fun consumeLastErrorJson(): String? = nativeConsumeLastErrorJson()

    override fun runInferenceStreamJson(
        engineHandle: Long,
        requestJson: String,
        callback: NativeTokenCallback,
    ): String = nativeRunInferenceStreamJson(engineHandle, requestJson, callback)

    override fun cancelInference(engineHandle: Long) {
        nativeCancelInference(engineHandle)
    }

    override fun destroyEngine(engineHandle: Long) {
        nativeDestroyEngine(engineHandle)
    }

    external fun nativeInitEngine(configJson: String): Long

    external fun nativeConsumeLastErrorJson(): String?

    external fun nativeRunInferenceStreamJson(
        engineHandle: Long,
        requestJson: String,
        callback: NativeTokenCallback,
    ): String

    external fun nativeCancelInference(engineHandle: Long)

    external fun nativeDestroyEngine(engineHandle: Long)
}

fun interface NativeTokenCallback {
    fun onTokenJson(tokenJson: String)
}
