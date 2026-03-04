package com.example.rwkvnotes.ai

import com.example.rwkvnotes.ai.protocol.ErrorJson
import com.example.rwkvnotes.ai.protocol.InferenceEvent
import com.example.rwkvnotes.ai.protocol.InferenceFinalJson
import com.example.rwkvnotes.ai.protocol.NativeFinalEnvelopeJson
import com.example.rwkvnotes.ai.protocol.InferenceRequestJson
import com.example.rwkvnotes.ai.protocol.TokenChunkJson
import com.example.rwkvnotes.config.AppConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class AiService @Inject constructor(
    private val appConfig: AppConfig,
    private val json: Json,
    private val nativeBridge: NativeRwkvBridge,
) : AiProcessor, ModelEngineReloader {
    private val promptAssembler = PromptAssembler()
    private var engineHandle: Long = nativeBridge.initEngine(json.encodeToString(appConfig))
    private var boundModelPath: String = appConfig.model.path
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null
    private var isShutdown = false
    private val stateLock = Any()
    private val cacheMaxEntries = appConfig.cache.maxEntries.coerceAtLeast(1)
    private val cacheLock = Any()
    private val resultCache = object : LinkedHashMap<String, InferenceFinalJson>(cacheMaxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, InferenceFinalJson>?): Boolean {
            return size > cacheMaxEntries
        }
    }

    override fun streamInference(userText: String): Flow<InferenceEvent> {
        if (isShutdown) {
            return flow {
                emit(InferenceEvent.Failed(ErrorJson("engine_shutdown", "native engine has been destroyed")))
            }
        }
        if (engineHandle <= 0L) {
            return flow {
                emit(
                    InferenceEvent.Failed(
                        ErrorJson("engine_init_failed", "native engine init failed, check model path and mmap load"),
                    ),
                )
            }
        }
        val normalizedInput = userText.trim()
        require(normalizedInput.isNotBlank()) { "input text is blank" }
        val cached = synchronized(cacheLock) { resultCache[normalizedInput] }
        if (cached != null) {
            return flow { emit(InferenceEvent.Completed(cached)) }
        }
        return callbackFlow {
            val request = InferenceRequestJson(
                prompt = promptAssembler.compose(
                    promptConfig = appConfig.prompt,
                    userText = normalizedInput,
                    contextWindowTokens = appConfig.model.contextWindowTokens,
                ),
                modelPath = boundModelPath,
                maxTokens = appConfig.model.maxTokens,
                temperature = appConfig.model.temperature,
                topP = appConfig.model.topP,
                stream = true,
            )
            val callback = NativeTokenCallback { tokenJson ->
                runCatching { json.decodeFromString<TokenChunkJson>(tokenJson) }
                    .onSuccess { trySend(InferenceEvent.Token(it)) }
                    .onFailure {
                        trySend(InferenceEvent.Failed(ErrorJson("token_parse_error", it.message ?: "unknown")))
                    }
            }
            nativeBridge.cancelInference(engineHandle)
            synchronized(stateLock) { activeJob?.cancel() }
            val launched = serviceScope.launch {
                runCatching {
                    nativeBridge.runInferenceStreamJson(
                        engineHandle = engineHandle,
                        requestJson = json.encodeToString(request),
                        callback = callback,
                    )
                }.onSuccess { resultJson ->
                    runCatching { json.decodeFromString<NativeFinalEnvelopeJson>(resultJson) }
                        .onSuccess {
                            if (it.ok && it.result != null) {
                                synchronized(cacheLock) { resultCache[normalizedInput] = it.result }
                                trySend(InferenceEvent.Completed(it.result))
                            } else {
                                val error = it.error ?: ErrorJson("native_error", "native returned no result")
                                trySend(InferenceEvent.Failed(error))
                            }
                        }
                        .onFailure {
                            trySend(
                                InferenceEvent.Failed(
                                    ErrorJson("result_parse_error", it.message ?: "unknown"),
                                ),
                            )
                        }
                    close()
                }.onFailure {
                    trySend(InferenceEvent.Failed(ErrorJson("native_inference_error", it.message ?: "unknown")))
                    close()
                }
            }
            synchronized(stateLock) { activeJob = launched }
            awaitClose {
                nativeBridge.cancelInference(engineHandle)
                synchronized(stateLock) {
                    activeJob?.cancel()
                    activeJob = null
                }
            }
        }
    }

    override suspend fun cancel() {
        if (engineHandle > 0L) nativeBridge.cancelInference(engineHandle)
        synchronized(stateLock) {
            activeJob?.cancel()
            activeJob = null
        }
    }

    override suspend fun shutdown() {
        if (isShutdown) return
        cancel()
        if (engineHandle > 0L) nativeBridge.destroyEngine(engineHandle)
        engineHandle = 0L
        isShutdown = true
    }

    override suspend fun reloadEngine(modelPath: String): Boolean {
        require(modelPath.isNotBlank()) { "modelPath is blank" }
        synchronized(stateLock) {
            if (isShutdown) return false
            activeJob?.cancel()
            activeJob = null
            if (engineHandle > 0L) {
                nativeBridge.cancelInference(engineHandle)
                nativeBridge.destroyEngine(engineHandle)
            }
            val refreshedConfig = appConfig.copy(model = appConfig.model.copy(path = modelPath))
            engineHandle = nativeBridge.initEngine(json.encodeToString(refreshedConfig))
            boundModelPath = modelPath
            synchronized(cacheLock) { resultCache.clear() }
            return engineHandle > 0L
        }
    }
}
