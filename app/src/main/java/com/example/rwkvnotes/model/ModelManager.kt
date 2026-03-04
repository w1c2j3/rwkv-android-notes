package com.example.rwkvnotes.model

import kotlinx.coroutines.flow.StateFlow

data class ModelDescriptor(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String?,
    val isActive: Boolean,
)

data class ModelRuntimeState(
    val activeModelPath: String,
    val mmapReadable: Boolean,
    val lastWarmupSuccess: Boolean,
)

data class ModelDownloadProgress(
    val modelName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val done: Boolean,
    val errorMessage: String? = null,
)

interface ModelManager {
    val models: StateFlow<List<ModelDescriptor>>
    val runtimeState: StateFlow<ModelRuntimeState>
    suspend fun refreshLocalModels()
    suspend fun downloadModel(
        modelName: String,
        primaryUrl: String,
        mirrorUrls: List<String> = emptyList(),
        expectedSha256: String? = null,
        maxRetriesPerSource: Int = 3,
    ): ModelDownloadProgress
    suspend fun switchModel(path: String): Boolean
    suspend fun warmup(): Boolean
    fun activeModelPath(): String
}
