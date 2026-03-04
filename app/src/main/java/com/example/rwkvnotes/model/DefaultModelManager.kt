package com.example.rwkvnotes.model

import android.content.Context
import com.example.rwkvnotes.config.AppConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class DefaultModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    appConfig: AppConfig,
) : ModelManager {
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
    private var activeModelPath: String = appConfig.model.path
    private val _models = MutableStateFlow<List<ModelDescriptor>>(emptyList())
    private val _runtimeState = MutableStateFlow(
        ModelRuntimeState(
            activeModelPath = activeModelPath,
            mmapReadable = File(activeModelPath).canRead(),
            lastWarmupSuccess = false,
        ),
    )

    override val models: StateFlow<List<ModelDescriptor>> = _models.asStateFlow()
    override val runtimeState: StateFlow<ModelRuntimeState> = _runtimeState.asStateFlow()

    override suspend fun refreshLocalModels() {
        val files = modelsDir.listFiles()?.filter { it.isFile } ?: emptyList()
        _models.value = files.map {
            ModelDescriptor(
                name = it.name,
                path = it.absolutePath,
                sizeBytes = it.length(),
                sha256 = null,
                isActive = it.absolutePath == activeModelPath,
            )
        }.sortedByDescending { it.sizeBytes }
        _runtimeState.value = _runtimeState.value.copy(mmapReadable = File(activeModelPath).canRead())
    }

    override suspend fun downloadModel(
        modelName: String,
        url: String,
        expectedSha256: String?,
    ): ModelDownloadProgress {
        val target = File(modelsDir, modelName)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        return runCatching {
            connection.connect()
            val total = connection.contentLengthLong.coerceAtLeast(-1L)
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = input.read(buffer)
                    var downloaded = 0L
                    while (read >= 0) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        read = input.read(buffer)
                    }
                    output.flush()
                    if (!expectedSha256.isNullOrBlank()) {
                        val actual = target.sha256()
                        require(actual.equals(expectedSha256, ignoreCase = true)) {
                            "sha256 mismatch"
                        }
                    }
                    refreshLocalModels()
                    ModelDownloadProgress(modelName, downloaded, total, done = true)
                }
            }
        }.getOrElse {
            if (target.exists()) target.delete()
            ModelDownloadProgress(
                modelName = modelName,
                downloadedBytes = 0L,
                totalBytes = -1L,
                done = true,
                errorMessage = it.message ?: "download failed",
            )
        }
    }

    override suspend fun switchModel(path: String): Boolean {
        val file = File(path)
        if (!file.exists() || !file.canRead()) return false
        activeModelPath = path
        _runtimeState.value = _runtimeState.value.copy(
            activeModelPath = activeModelPath,
            mmapReadable = true,
        )
        refreshLocalModels()
        return true
    }

    override suspend fun warmup(): Boolean {
        val ok = File(activeModelPath).exists()
        _runtimeState.value = _runtimeState.value.copy(lastWarmupSuccess = ok, mmapReadable = ok)
        return ok
    }

    override fun activeModelPath(): String = activeModelPath
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var read = input.read(buffer)
        while (read >= 0) {
            digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
