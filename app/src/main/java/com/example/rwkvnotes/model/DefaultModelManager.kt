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
        primaryUrl: String,
        mirrorUrls: List<String>,
        expectedSha256: String?,
        maxRetriesPerSource: Int,
    ): ModelDownloadProgress {
        val target = File(modelsDir, modelName)
        val partial = File(modelsDir, "$modelName.part")
        require(primaryUrl.isNotBlank()) { "primaryUrl is blank" }
        require(maxRetriesPerSource > 0) { "maxRetriesPerSource must be > 0" }
        val sources = buildList {
            add(primaryUrl)
            mirrorUrls.forEach { if (it.isNotBlank() && it != primaryUrl) add(it) }
        }
        val failures = mutableListOf<String>()
        sources.forEach { source ->
            repeat(maxRetriesPerSource) { attempt ->
                val result = runCatching {
                    val resumedBytes = if (partial.exists()) partial.length() else 0L
                    val connection = (URL(source).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        if (resumedBytes > 0L) setRequestProperty("Range", "bytes=$resumedBytes-")
                    }
                    try {
                        connection.connect()
                        val code = connection.responseCode
                        if (code !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                            throw IllegalStateException("http code=$code")
                        }
                        val totalFromHeader = when (code) {
                            HttpURLConnection.HTTP_PARTIAL -> {
                                val contentRange = connection.getHeaderField("Content-Range").orEmpty()
                                contentRange.substringAfterLast("/").toLongOrNull() ?: -1L
                            }
                            else -> connection.contentLengthLong.coerceAtLeast(-1L)
                        }
                        val append = code == HttpURLConnection.HTTP_PARTIAL && resumedBytes > 0L
                        if (!append && partial.exists()) partial.delete()
                        connection.inputStream.use { input ->
                            FileOutputStream(partial, append).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var read = input.read(buffer)
                                while (read >= 0) {
                                    output.write(buffer, 0, read)
                                    read = input.read(buffer)
                                }
                                output.flush()
                            }
                        }
                        if (target.exists()) target.delete()
                        require(partial.renameTo(target)) { "failed to finalize model file" }
                        if (!expectedSha256.isNullOrBlank()) {
                            val actual = target.sha256()
                            require(actual.equals(expectedSha256, ignoreCase = true)) { "sha256 mismatch" }
                        }
                        refreshLocalModels()
                        ModelDownloadProgress(
                            modelName = modelName,
                            downloadedBytes = target.length(),
                            totalBytes = totalFromHeader,
                            done = true,
                        )
                    } finally {
                        connection.disconnect()
                    }
                }
                if (result.isSuccess) return result.getOrThrow()
                val message = result.exceptionOrNull()?.message ?: "unknown download error"
                failures += "source=$source attempt=${attempt + 1} error=$message"
            }
        }
        if (target.exists()) target.delete()
        if (partial.exists()) partial.delete()
        return ModelDownloadProgress(
            modelName = modelName,
            downloadedBytes = 0L,
            totalBytes = -1L,
            done = true,
            errorMessage = failures.joinToString(" | "),
        )
    }

    override suspend fun switchModel(path: String): Boolean {
        val file = File(path)
        require(file.exists()) { "model file not found: $path" }
        require(file.canRead()) { "model file unreadable: $path" }
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
