package com.example.rwkvnotes.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
    private val requiredRuntimeExtension = normalizeRuntimeExtension(appConfig.model.runtimeExtension)
    private var activeModelPath: String = appConfig.model.path
    private val _models = MutableStateFlow<List<ModelDescriptor>>(emptyList())
    private val _runtimeState = MutableStateFlow(
        ModelRuntimeState(
            activeModelPath = activeModelPath,
            mmapReadable = activeModelReadable(),
            lastWarmupSuccess = false,
            requiredRuntimeExtension = requiredRuntimeExtension,
        ),
    )

    override val models: StateFlow<List<ModelDescriptor>> = _models.asStateFlow()
    override val runtimeState: StateFlow<ModelRuntimeState> = _runtimeState.asStateFlow()

    override suspend fun refreshLocalModels() {
        val files = modelsDir.listFiles()?.filter {
            it.isFile &&
                !it.name.endsWith(".part") &&
                matchesRuntimeExtension(it.name, requiredRuntimeExtension)
        } ?: emptyList()
        _models.value = files.map {
            ModelDescriptor(
                name = it.name,
                path = it.absolutePath,
                sizeBytes = it.length(),
                sha256 = null,
                isActive = it.absolutePath == activeModelPath,
            )
        }.sortedByDescending { it.sizeBytes }
        _runtimeState.value = _runtimeState.value.copy(
            mmapReadable = activeModelReadable(),
            requiredRuntimeExtension = requiredRuntimeExtension,
        )
    }

    override suspend fun importModel(modelUri: Uri, preferredFileName: String?): ModelDescriptor {
        val displayName = preferredFileName?.trim()?.ifBlank { null } ?: queryDisplayName(modelUri)
        val targetName = normalizeImportedModelFileName(displayName ?: modelUri.lastPathSegment, requiredRuntimeExtension)
        val target = File(modelsDir, targetName)
        val partial = File(modelsDir, "$targetName.part")
        var copiedBytes = 0L
        try {
            val input = context.contentResolver.openInputStream(modelUri)
                ?: throw IllegalArgumentException("cannot open model uri")
            input.use { stream ->
                FileOutputStream(partial, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = stream.read(buffer)
                    while (read >= 0) {
                        output.write(buffer, 0, read)
                        copiedBytes += read
                        read = stream.read(buffer)
                    }
                    output.flush()
                }
            }
            require(copiedBytes > 0L) { "imported model is empty" }
            if (target.exists()) {
                require(target.delete()) { "failed to replace existing model file" }
            }
            require(partial.renameTo(target)) { "failed to finalize imported model" }
            refreshLocalModels()
            return _models.value.firstOrNull { it.path == target.absolutePath }
                ?: ModelDescriptor(
                    name = target.name,
                    path = target.absolutePath,
                    sizeBytes = target.length(),
                    sha256 = null,
                    isActive = target.absolutePath == activeModelPath,
                )
        } catch (throwable: Throwable) {
            partial.delete()
            throw throwable
        }
    }

    override suspend fun downloadModel(
        modelName: String,
        primaryUrl: String,
        mirrorUrls: List<String>,
        expectedSha256: String?,
        maxRetriesPerSource: Int,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)?,
    ): ModelDownloadProgress {
        val target = File(modelsDir, modelName)
        val partial = File(modelsDir, "$modelName.part")
        require(matchesRuntimeExtension(modelName, requiredRuntimeExtension)) {
            "modelName must end with $requiredRuntimeExtension"
        }
        require(primaryUrl.isNotBlank()) { "primaryUrl is blank" }
        require(!expectedSha256.isNullOrBlank()) { "expectedSha256 is blank" }
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
                        var downloaded = resumedBytes
                        onProgress?.invoke(downloaded, totalFromHeader)
                        val append = code == HttpURLConnection.HTTP_PARTIAL && resumedBytes > 0L
                        if (!append && partial.exists()) partial.delete()
                        connection.inputStream.use { input ->
                            FileOutputStream(partial, append).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var read = input.read(buffer)
                                var reportCounter = 0
                                while (read >= 0) {
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    reportCounter += read
                                    if (reportCounter >= 512 * 1024) {
                                        onProgress?.invoke(downloaded, totalFromHeader)
                                        reportCounter = 0
                                    }
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
                        onProgress?.invoke(target.length(), totalFromHeader)
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
            errorCode = classifyFailureCode(failures.joinToString(" | ")),
            errorMessage = failures.joinToString(" | "),
        )
    }

    override suspend fun switchModel(path: String): Boolean {
        val file = File(path)
        require(file.exists()) { "model file not found: $path" }
        require(file.canRead()) { "model file unreadable: $path" }
        require(matchesRuntimeExtension(file.name, requiredRuntimeExtension)) {
            "unsupported model format: ${file.name}"
        }
        activeModelPath = path
        _runtimeState.value = _runtimeState.value.copy(
            activeModelPath = activeModelPath,
            mmapReadable = activeModelReadable(),
            lastWarmupSuccess = false,
            lastErrorMessage = null,
            requiredRuntimeExtension = requiredRuntimeExtension,
        )
        refreshLocalModels()
        return true
    }

    override suspend fun warmup(): Boolean {
        val file = File(activeModelPath)
        val errorMessage = warmupPrecheckError(file)
        _runtimeState.value = _runtimeState.value.copy(
            lastWarmupSuccess = false,
            mmapReadable = activeModelReadable(file),
            lastErrorMessage = errorMessage,
            requiredRuntimeExtension = requiredRuntimeExtension,
        )
        val ok = errorMessage == null
        return ok
    }

    override suspend fun recordWarmupResult(success: Boolean, message: String?) {
        _runtimeState.value = _runtimeState.value.copy(
            lastWarmupSuccess = success,
            mmapReadable = activeModelReadable(),
            lastErrorMessage = message,
            requiredRuntimeExtension = requiredRuntimeExtension,
        )
    }

    override fun activeModelPath(): String = activeModelPath

    private fun activeModelReadable(file: File = File(activeModelPath)): Boolean {
        return file.exists() && file.isFile && file.canRead()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex >= 0 && cursor.moveToFirst()) cursor.getString(columnIndex) else null
            }
    }

    private fun warmupPrecheckError(file: File): String? {
        return when {
            activeModelPath.isBlank() -> "active model path is blank"
            !file.exists() -> "active model file not found: $activeModelPath"
            !file.isFile -> "active model path is not a file: $activeModelPath"
            !file.canRead() -> "active model file unreadable: $activeModelPath"
            !matchesRuntimeExtension(file.name, requiredRuntimeExtension) ->
                "active model must end with $requiredRuntimeExtension"
            else -> null
        }
    }
}

private fun classifyFailureCode(message: String): String {
    val text = message.lowercase()
    return when {
        "sha256 mismatch" in text -> "CHECKSUM_MISMATCH"
        "failed to finalize model file" in text -> "FINALIZE_FAILED"
        "http code=" in text -> "HTTP_STATUS"
        "primaryurl is blank" in text ||
            "expectedsha256 is blank" in text ||
            "maxretriespersource must be > 0" in text ||
            "modelname must end with" in text -> "INVALID_INPUT"
        "timeout" in text || "unreachable" in text || "unknownhost" in text -> "NETWORK_IO"
        "permission denied" in text || "no space" in text -> "FILE_IO"
        else -> "ALL_SOURCES_FAILED"
    }
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
