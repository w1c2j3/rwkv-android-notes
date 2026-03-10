package com.example.rwkvnotes.model

import android.net.Uri
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
    val lastErrorMessage: String? = null,
    val requiredRuntimeExtension: String = ".bin",
)

data class ModelDownloadProgress(
    val modelName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val done: Boolean,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

interface ModelManager {
    val models: StateFlow<List<ModelDescriptor>>
    val runtimeState: StateFlow<ModelRuntimeState>
    suspend fun refreshLocalModels()
    suspend fun importModel(modelUri: Uri, preferredFileName: String? = null): ModelDescriptor
    suspend fun downloadModel(
        modelName: String,
        primaryUrl: String,
        mirrorUrls: List<String> = emptyList(),
        expectedSha256: String? = null,
        maxRetriesPerSource: Int = 3,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): ModelDownloadProgress
    suspend fun switchModel(path: String): Boolean
    suspend fun warmup(): Boolean
    suspend fun recordWarmupResult(success: Boolean, message: String? = null)
    fun activeModelPath(): String
}
