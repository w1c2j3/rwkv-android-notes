package com.example.rwkvnotes.ai

import com.example.rwkvnotes.ai.protocol.ErrorJson
import com.example.rwkvnotes.ai.protocol.InferenceEvent
import com.example.rwkvnotes.ai.protocol.InferenceFinalJson
import com.example.rwkvnotes.ai.protocol.NativeFinalEnvelopeJson
import com.example.rwkvnotes.ai.protocol.InferenceRequestJson
import com.example.rwkvnotes.ai.protocol.TokenChunkJson
import com.example.rwkvnotes.ai.protocol.normalizeNativeError
import com.example.rwkvnotes.ai.protocol.validateOrError
import com.example.rwkvnotes.config.AppConfig
import com.example.rwkvnotes.config.InferenceSettings
import com.example.rwkvnotes.config.InferenceSettingsStore
import com.example.rwkvnotes.infer.InferenceRuntime
import com.example.rwkvnotes.infer.NativeTokenCallback
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class AiService @Inject constructor(
    private val appConfig: AppConfig,
    settingsStore: InferenceSettingsStore,
    private val json: Json,
    private val nativeBridge: InferenceRuntime,
) : AiProcessor, ModelEngineReloader {
    private val promptAssembler = PromptAssembler()
    private val resultPostProcessor = InferenceResultPostProcessor()
    private var engineHandle: Long = 0L
    private var boundModelPath: String = appConfig.model.path
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null
    private var isShutdown = false
    @Volatile
    private var lastEngineError: ErrorJson? = null
    private val stateLock = Any()
    @Volatile
    private var currentSettings = InferenceSettings(
        maxTokens = appConfig.model.maxTokens,
        temperature = appConfig.model.temperature,
        topP = appConfig.model.topP,
    )
    private val cacheMaxEntries = appConfig.cache.maxEntries.coerceAtLeast(1)
    private val cacheLock = Any()
    private val resultCache = object : LinkedHashMap<String, InferenceFinalJson>(cacheMaxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, InferenceFinalJson>?): Boolean {
            return size > cacheMaxEntries
        }
    }

    init {
        engineHandle = initEngine(appConfig)
        serviceScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                currentSettings = settings
            }
        }
    }

    override fun streamInference(userText: String): Flow<InferenceEvent> {
        if (isShutdown) {
            return flow {
                emit(InferenceEvent.Failed(ErrorJson("ENGINE_SHUTDOWN", "native engine has been destroyed")))
            }
        }
        if (engineHandle <= 0L) {
            val error = lastEngineError ?: ErrorJson(
                "ENGINE_INIT_FAILED",
                "native engine init failed, check model path and mmap load",
            )
            return flow {
                emit(InferenceEvent.Failed(error))
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
                maxTokens = currentSettings.maxTokens,
                temperature = currentSettings.temperature,
                topP = currentSettings.topP,
                topK = appConfig.inferSampling.topK,
                repeatPenalty = appConfig.inferSampling.repeatPenalty,
                stream = true,
            )
            val validationError = request.validateOrError()
            if (validationError != null) {
                trySend(InferenceEvent.Failed(validationError))
                close()
                return@callbackFlow
            }
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
                                val normalizedResult = resultPostProcessor.normalize(it.result, appConfig.tagging)
                                synchronized(cacheLock) { resultCache[normalizedInput] = normalizedResult }
                                trySend(InferenceEvent.Completed(normalizedResult))
                            } else {
                                val error = it.error ?: ErrorJson("native_error", "native returned no result")
                                trySend(InferenceEvent.Failed(normalizeNativeError(error)))
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
        lastEngineError = null
        isShutdown = true
    }

    override suspend fun reloadEngine(modelPath: String): EngineReloadResult {
        require(modelPath.isNotBlank()) { "modelPath is blank" }
        synchronized(stateLock) {
            if (isShutdown) {
                return EngineReloadResult(
                    success = false,
                    errorMessage = "ENGINE_SHUTDOWN: native engine has been destroyed",
                )
            }
            activeJob?.cancel()
            activeJob = null
            if (engineHandle > 0L) {
                nativeBridge.cancelInference(engineHandle)
                nativeBridge.destroyEngine(engineHandle)
            }
            val refreshedConfig = appConfig.copy(model = appConfig.model.copy(path = modelPath))
            val reloadedHandle = initEngine(refreshedConfig)
            engineHandle = reloadedHandle
            if (reloadedHandle > 0L) {
                boundModelPath = modelPath
            }
            synchronized(cacheLock) { resultCache.clear() }
            if (reloadedHandle > 0L) {
                return EngineReloadResult(success = true)
            }
            return EngineReloadResult(
                success = false,
                errorMessage = formatEngineError(lastEngineError) ?: "native engine init failed",
            )
        }
    }

    private fun initEngine(config: AppConfig): Long {
        val handle = nativeBridge.initEngine(json.encodeToString(config))
        lastEngineError = if (handle > 0L) {
            null
        } else {
            consumeNativeError(
                defaultCode = "ENGINE_INIT_FAILED",
                defaultMessage = "native engine init failed",
            )
        }
        return handle
    }

    private fun consumeNativeError(defaultCode: String, defaultMessage: String): ErrorJson {
        val errorJson = nativeBridge.consumeLastErrorJson()
        if (errorJson.isNullOrBlank()) {
            return ErrorJson(defaultCode, defaultMessage)
        }
        val parsed = runCatching {
            val envelope = json.decodeFromString<NativeFinalEnvelopeJson>(errorJson)
            envelope.error
        }.getOrNull() ?: return ErrorJson(defaultCode, defaultMessage)
        return normalizeNativeError(parsed)
    }

    private fun formatEngineError(error: ErrorJson?): String? {
        val normalized = error?.let(::normalizeNativeError) ?: return null
        return "${normalized.code}: ${normalized.message}"
    }
}
