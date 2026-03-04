package com.example.rwkvnotes.ai

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeRwkvBridge @Inject constructor() {
    init {
        System.loadLibrary("rwkv_jni")
    }

    fun initEngine(configJson: String): Long = nativeInitEngine(configJson)

    fun runInferenceStreamJson(
        engineHandle: Long,
        requestJson: String,
        callback: NativeTokenCallback,
    ): String = nativeRunInferenceStreamJson(engineHandle, requestJson, callback)

    fun cancelInference(engineHandle: Long) {
        nativeCancelInference(engineHandle)
    }

    fun destroyEngine(engineHandle: Long) {
        nativeDestroyEngine(engineHandle)
    }

    external fun nativeInitEngine(configJson: String): Long
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
