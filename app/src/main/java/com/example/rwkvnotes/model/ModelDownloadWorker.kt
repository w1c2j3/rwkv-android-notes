package com.example.rwkvnotes.model

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val modelManager: ModelManager,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val modelName = inputData.getString(KEY_MODEL_NAME).orEmpty()
        val primaryUrl = inputData.getString(KEY_PRIMARY_URL).orEmpty()
        val mirrors = inputData.getStringArray(KEY_MIRROR_URLS)?.toList().orEmpty()
        val expectedSha = inputData.getString(KEY_EXPECTED_SHA256)?.ifBlank { null }
        val retries = inputData.getInt(KEY_MAX_RETRIES_PER_SOURCE, 3)
        if (modelName.isBlank() || primaryUrl.isBlank() || expectedSha.isNullOrBlank()) {
            return Result.failure(
                workDataOf(
                    KEY_MODEL_NAME to modelName,
                    KEY_ERROR_CODE to "INVALID_INPUT",
                    KEY_MESSAGE to "invalid download input",
                ),
            )
        }
        setProgress(workDataOf(KEY_MODEL_NAME to modelName, KEY_DOWNLOADED_BYTES to 0L, KEY_TOTAL_BYTES to -1L))
        val progress = modelManager.downloadModel(
            modelName = modelName,
            primaryUrl = primaryUrl,
            mirrorUrls = mirrors,
            expectedSha256 = expectedSha,
            maxRetriesPerSource = retries,
            onProgress = { downloaded, total ->
                setProgressAsync(
                    workDataOf(
                        KEY_MODEL_NAME to modelName,
                        KEY_DOWNLOADED_BYTES to downloaded,
                        KEY_TOTAL_BYTES to total,
                    ),
                )
            },
        )
        val output = outputData(progress)
        return if (progress.errorMessage == null) Result.success(output) else Result.failure(output)
    }

    private fun outputData(progress: ModelDownloadProgress): Data {
        return workDataOf(
            KEY_MODEL_NAME to progress.modelName,
            KEY_DOWNLOADED_BYTES to progress.downloadedBytes,
            KEY_TOTAL_BYTES to progress.totalBytes,
            KEY_ERROR_CODE to progress.errorCode,
            KEY_MESSAGE to progress.errorMessage,
        )
    }

    companion object {
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_PRIMARY_URL = "primary_url"
        const val KEY_MIRROR_URLS = "mirror_urls"
        const val KEY_EXPECTED_SHA256 = "expected_sha256"
        const val KEY_MAX_RETRIES_PER_SOURCE = "max_retries_per_source"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_ERROR_CODE = "error_code"
        const val KEY_MESSAGE = "message"
    }
}
