package com.example.rwkvnotes.model

import com.example.rwkvnotes.ai.ModelEngineReloader
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

@Singleton
class ModelTaskScheduler @Inject constructor(
    private val modelManager: ModelManager,
    private val engineReloader: ModelEngineReloader,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val models: StateFlow<List<ModelDescriptor>> = modelManager.models
    val runtimeState: StateFlow<ModelRuntimeState> = modelManager.runtimeState

    suspend fun refreshModels() {
        withContext(ioDispatcher) {
            modelManager.refreshLocalModels()
        }
    }

    suspend fun warmupModel(): Boolean {
        return withContext(ioDispatcher) {
            modelManager.warmup()
        }
    }

    suspend fun switchModel(path: String): Boolean {
        require(path.isNotBlank()) { "model path is blank" }
        return withContext(ioDispatcher) {
            val switched = modelManager.switchModel(path)
            require(switched) { "model switch rejected: $path" }
            val reloaded = engineReloader.reloadEngine(path)
            require(reloaded) { "engine reload failed after model switch: $path" }
            true
        }
    }

    suspend fun downloadModel(
        modelName: String,
        primaryUrl: String,
        mirrorUrls: List<String> = emptyList(),
        expectedSha256: String? = null,
        maxRetriesPerSource: Int = 3,
    ): ModelDownloadProgress {
        return withContext(ioDispatcher) {
            modelManager.downloadModel(
                modelName = modelName,
                primaryUrl = primaryUrl,
                mirrorUrls = mirrorUrls,
                expectedSha256 = expectedSha256,
                maxRetriesPerSource = maxRetriesPerSource,
            )
        }
    }
}
