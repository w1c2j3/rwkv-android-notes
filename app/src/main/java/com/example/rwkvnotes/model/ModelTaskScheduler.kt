package com.example.rwkvnotes.model

import android.net.Uri
import com.example.rwkvnotes.ai.ModelEngineReloader
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

@Singleton
class ModelTaskScheduler @Inject constructor(
    private val modelManager: ModelManager,
    private val engineReloader: ModelEngineReloader,
    private val workManager: WorkManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val runtimeCoordinator = ModelRuntimeCoordinator(modelManager, engineReloader)
    val models: StateFlow<List<ModelDescriptor>> = modelManager.models
    val runtimeState: StateFlow<ModelRuntimeState> = modelManager.runtimeState

    suspend fun refreshModels() {
        withContext(ioDispatcher) {
            modelManager.refreshLocalModels()
        }
    }

    suspend fun warmupModel(): Boolean {
        return withContext(ioDispatcher) {
            runtimeCoordinator.warmupActiveModel()
        }
    }

    suspend fun importModel(uri: Uri, preferredFileName: String? = null): ModelDescriptor {
        return withContext(ioDispatcher) {
            modelManager.importModel(uri, preferredFileName)
        }
    }

    suspend fun switchModel(path: String): Boolean {
        require(path.isNotBlank()) { "model path is blank" }
        return withContext(ioDispatcher) {
            runtimeCoordinator.switchActiveModel(path)
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

    fun enqueueDownload(
        modelName: String,
        primaryUrl: String,
        mirrorUrls: List<String>,
        expectedSha256: String?,
        maxRetriesPerSource: Int,
    ): UUID {
        val input = workDataOf(
            ModelDownloadWorker.KEY_MODEL_NAME to modelName,
            ModelDownloadWorker.KEY_PRIMARY_URL to primaryUrl,
            ModelDownloadWorker.KEY_MIRROR_URLS to mirrorUrls.toTypedArray(),
            ModelDownloadWorker.KEY_EXPECTED_SHA256 to (expectedSha256 ?: ""),
            ModelDownloadWorker.KEY_MAX_RETRIES_PER_SOURCE to maxRetriesPerSource,
        )
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(input)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10.seconds.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork("download-$modelName", ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    fun observeDownloadWork(id: UUID): Flow<ModelDownloadWorkState> {
        return workManager.getWorkInfoByIdFlow(id).map { info ->
            if (info == null) {
                return@map ModelDownloadWorkState(
                    modelName = "unknown",
                    state = WorkInfo.State.CANCELLED,
                    downloadedBytes = 0L,
                    totalBytes = -1L,
                    errorCode = "NO_WORK_INFO",
                    message = null,
                )
            }
            ModelDownloadWorkState(
                modelName = info.outputData.getString(ModelDownloadWorker.KEY_MODEL_NAME)
                    ?: info.progress.getString(ModelDownloadWorker.KEY_MODEL_NAME).orEmpty(),
                state = info.state,
                downloadedBytes = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L),
                totalBytes = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, -1L),
                errorCode = info.outputData.getString(ModelDownloadWorker.KEY_ERROR_CODE),
                message = info.outputData.getString(ModelDownloadWorker.KEY_MESSAGE),
            )
        }
    }
}

data class ModelDownloadWorkState(
    val modelName: String,
    val state: WorkInfo.State,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val errorCode: String?,
    val message: String?,
)
