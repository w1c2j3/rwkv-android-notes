package com.example.rwkvnotes.note

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.rwkvnotes.ai.protocol.InferenceEvent
import com.example.rwkvnotes.config.AppConfig
import com.example.rwkvnotes.config.InferenceSettings
import com.example.rwkvnotes.config.InferenceSettingsStore
import com.example.rwkvnotes.model.ModelDescriptor
import com.example.rwkvnotes.model.ModelTaskScheduler
import com.example.rwkvnotes.obs.InferenceMetrics
import com.example.rwkvnotes.orchestrator.InferenceTaskOrchestrator
import com.example.rwkvnotes.orchestrator.OrchestratorEvent
import com.example.rwkvnotes.index.SemanticIndexService
import java.util.UUID
import kotlin.math.max
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppScreen {
    HOME, STREAM, RESULT, HISTORY, MODEL, SETTINGS
}

data class NoteRecordUi(
    val id: Long,
    val markdown: String,
    val tags: List<String>,
)

data class NoteUiState(
    val inputText: String = "",
    val streamingText: String = "",
    val lastMarkdown: String = "",
    val lastTags: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
    val recentHistory: List<NoteRecordUi> = emptyList(),
    val history: List<NoteRecordUi> = emptyList(),
    val currentScreen: AppScreen = AppScreen.HOME,
    val metrics: InferenceMetrics = InferenceMetrics(),
    val activeModelPath: String = "",
    val mmapReadable: Boolean = false,
    val lastWarmupSuccess: Boolean = false,
    val runtimeErrorMessage: String? = null,
    val requiredRuntimeExtension: String = ".bin",
    val models: List<ModelDescriptor> = emptyList(),
    val modelActionMessage: String? = null,
    val downloadWorkId: String? = null,
    val downloadState: String? = null,
    val downloadProgressText: String? = null,
    val downloadErrorCode: String? = null,
    val downloadErrorText: String? = null,
    val downloadSpeedText: String? = null,
    val downloadEtaText: String? = null,
    val historyQueryInput: String = "",
    val historyAppliedQuery: String = "",
    val historySelectedTag: String? = null,
    val historyAvailableTags: List<String> = emptyList(),
    val historyPageIndex: Int = 0,
    val historyHasNextPage: Boolean = false,
    val historyLoading: Boolean = false,
    val historyErrorMessage: String? = null,
    val settingsMaxTokensInput: String = "",
    val settingsTemperatureInput: String = "",
    val settingsTopPInput: String = "",
    val settingsMessage: String? = null,
)

@HiltViewModel
class NoteViewModel @Inject constructor(
    private val orchestrator: InferenceTaskOrchestrator,
    private val noteRepository: NoteRepository,
    private val modelTaskScheduler: ModelTaskScheduler,
    private val appConfig: AppConfig,
    private val settingsStore: InferenceSettingsStore,
    private val semanticIndexService: SemanticIndexService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NoteUiState())
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()
    private var inferenceJob: Job? = null
    private var downloadJob: Job? = null
    private val streamingBuffer = StringBuilder()
    private var runningRawInput: String = ""
    private var lastDownloadBytes: Long = 0L
    private var lastDownloadTs: Long = 0L

    init {
        viewModelScope.launch {
            noteRepository.observeNotes().collectLatest { notes ->
                semanticIndexService.rebuild(notes)
                _uiState.update {
                    it.copy(
                        recentHistory = notes.take(3).map(::toNoteRecordUi),
                    )
                }
                loadHistoryPage(_uiState.value.historyPageIndex)
            }
        }
        viewModelScope.launch {
            modelTaskScheduler.refreshModels()
            modelTaskScheduler.runtimeState.collectLatest { runtime ->
                _uiState.update {
                    it.copy(
                        activeModelPath = runtime.activeModelPath,
                        mmapReadable = runtime.mmapReadable,
                        lastWarmupSuccess = runtime.lastWarmupSuccess,
                        runtimeErrorMessage = runtime.lastErrorMessage,
                        requiredRuntimeExtension = runtime.requiredRuntimeExtension,
                    )
                }
            }
        }
        viewModelScope.launch {
            modelTaskScheduler.models.collectLatest { models ->
                _uiState.update { it.copy(models = models) }
            }
        }
        viewModelScope.launch {
            orchestrator.metricsService.metrics.collectLatest { metrics ->
                _uiState.update { it.copy(metrics = metrics) }
            }
        }
        viewModelScope.launch {
            semanticIndexService.snapshot.collectLatest { snapshot ->
                _uiState.update {
                    it.copy(historyAvailableTags = snapshot.byTag.keys.sorted())
                }
            }
        }
        viewModelScope.launch {
            settingsStore.settingsFlow.collectLatest { settings ->
                _uiState.update {
                    it.copy(
                        settingsMaxTokensInput = settings.maxTokens.toString(),
                        settingsTemperatureInput = settings.temperature.toString(),
                        settingsTopPInput = settings.topP.toString(),
                    )
                }
            }
        }
    }

    fun onInputChanged(value: String) {
        _uiState.update { it.copy(inputText = value, errorMessage = null) }
    }

    fun runRestructure() {
        val input = _uiState.value.inputText.trim()
        if (input.isBlank()) {
            showError("input text is blank")
            return
        }
        runFromText(input)
    }

    fun runFromUri(uri: Uri) {
        inferenceJob?.cancel()
        inferenceJob = viewModelScope.launch {
            runCatching {
                orchestrator.cancel()
                streamingBuffer.clear()
                runningRawInput = ""
                _uiState.update {
                    it.copy(
                        isRunning = true,
                        errorMessage = null,
                        noticeMessage = null,
                        streamingText = "",
                        lastMarkdown = "",
                        lastTags = emptyList(),
                        currentScreen = AppScreen.STREAM,
                    )
                }
                orchestrator.runFromUri(uri).collectLatest { event ->
                    handleOrchestratorEvent(event, "")
                }
            }.onFailure {
                showError(it.message ?: "run from uri failed")
                _uiState.update { state -> state.copy(isRunning = false) }
            }
            inferenceJob = null
        }
    }

    private fun runFromText(input: String) {
        inferenceJob?.cancel()
        inferenceJob = viewModelScope.launch {
            runCatching {
                orchestrator.cancel()
                streamingBuffer.clear()
                runningRawInput = input
                _uiState.update {
                    it.copy(
                        isRunning = true,
                        errorMessage = null,
                        noticeMessage = null,
                        streamingText = "",
                        lastMarkdown = "",
                        lastTags = emptyList(),
                        currentScreen = AppScreen.STREAM,
                    )
                }
                orchestrator.runFromText(input).collectLatest { event ->
                    handleOrchestratorEvent(event, input)
                }
            }.onFailure {
                showError(it.message ?: "run from text failed")
                _uiState.update { state -> state.copy(isRunning = false) }
            }
            inferenceJob = null
        }
    }

    private suspend fun handleOrchestratorEvent(event: OrchestratorEvent, rawInput: String) {
        when (event) {
            is OrchestratorEvent.Started -> {
                if (event.rawInput.isNotBlank()) {
                    runningRawInput = event.rawInput
                }
            }
            is OrchestratorEvent.Failed -> {
                showError(event.message)
                _uiState.update { it.copy(isRunning = false) }
            }
            is OrchestratorEvent.Model -> when (val modelEvent = event.event) {
                is InferenceEvent.Token -> {
                    streamingBuffer.append(modelEvent.chunk.token)
                    _uiState.update { old ->
                        old.copy(streamingText = streamingBuffer.toString())
                    }
                }
                is InferenceEvent.Completed -> {
                    noteRepository.saveInference(runningRawInput.ifBlank { rawInput }, modelEvent.result)
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            lastMarkdown = modelEvent.result.markdown,
                            lastTags = modelEvent.result.tags,
                            currentScreen = AppScreen.RESULT,
                        )
                    }
                }
                is InferenceEvent.Failed -> {
                    showError("${modelEvent.error.code}: ${modelEvent.error.message}")
                    _uiState.update { it.copy(isRunning = false) }
                }
            }
        }
    }

    fun navigate(screen: AppScreen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearNoticeMessage() {
        _uiState.update { it.copy(noticeMessage = null) }
    }

    fun showNotice(message: String) {
        if (message.isBlank()) return
        _uiState.update { it.copy(noticeMessage = message, errorMessage = null) }
    }

    fun showError(message: String) {
        if (message.isBlank()) return
        _uiState.update { it.copy(errorMessage = message, noticeMessage = null) }
    }

    fun onHistoryQueryChanged(value: String) {
        _uiState.update { it.copy(historyQueryInput = value, historyErrorMessage = null) }
    }

    fun applyHistoryFilters() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    historyAppliedQuery = it.historyQueryInput.trim(),
                    historyErrorMessage = null,
                )
            }
            loadHistoryPage(0)
        }
    }

    fun selectHistoryTag(tag: String?) {
        viewModelScope.launch {
            val normalizedTag = tag?.trim()?.ifBlank { null }
            _uiState.update {
                it.copy(
                    historySelectedTag = if (it.historySelectedTag == normalizedTag) null else normalizedTag,
                    historyAppliedQuery = it.historyQueryInput.trim(),
                    historyErrorMessage = null,
                )
            }
            loadHistoryPage(0)
        }
    }

    fun clearHistoryFilters() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    historyQueryInput = "",
                    historyAppliedQuery = "",
                    historySelectedTag = null,
                    historyErrorMessage = null,
                )
            }
            loadHistoryPage(0)
        }
    }

    fun loadPreviousHistoryPage() {
        val previous = (_uiState.value.historyPageIndex - 1).coerceAtLeast(0)
        if (previous == _uiState.value.historyPageIndex) return
        viewModelScope.launch { loadHistoryPage(previous) }
    }

    fun loadNextHistoryPage() {
        if (!_uiState.value.historyHasNextPage) return
        viewModelScope.launch { loadHistoryPage(_uiState.value.historyPageIndex + 1) }
    }

    fun onSettingsMaxTokensChanged(value: String) {
        _uiState.update { it.copy(settingsMaxTokensInput = value, settingsMessage = null) }
    }

    fun onSettingsTemperatureChanged(value: String) {
        _uiState.update { it.copy(settingsTemperatureInput = value, settingsMessage = null) }
    }

    fun onSettingsTopPChanged(value: String) {
        _uiState.update { it.copy(settingsTopPInput = value, settingsMessage = null) }
    }

    fun saveInferenceSettings() {
        viewModelScope.launch {
            val draft = runCatching {
                parseInferenceSettingsDraft(
                    maxTokensText = _uiState.value.settingsMaxTokensInput,
                    temperatureText = _uiState.value.settingsTemperatureInput,
                    topPText = _uiState.value.settingsTopPInput,
                )
            }
            draft.onFailure {
                _uiState.update { state -> state.copy(settingsMessage = it.message ?: "settings invalid") }
                return@launch
            }
            val settings = draft.getOrThrow()
            runCatching {
                settingsStore.update(
                    maxTokens = settings.maxTokens,
                    temperature = settings.temperature,
                    topP = settings.topP,
                )
            }.onSuccess {
                _uiState.update { state -> state.copy(settingsMessage = "settings saved") }
            }.onFailure {
                _uiState.update { state -> state.copy(settingsMessage = it.message ?: "failed to save settings") }
            }
        }
    }

    fun stopInference() {
        inferenceJob?.cancel()
        viewModelScope.launch { orchestrator.cancel() }
        _uiState.update { it.copy(isRunning = false) }
    }

    fun warmupModel() {
        viewModelScope.launch {
            val ok = modelTaskScheduler.warmupModel()
            val runtimeMessage = modelTaskScheduler.runtimeState.value.lastErrorMessage
            _uiState.update {
                it.copy(modelActionMessage = if (ok) "warmup success" else runtimeMessage ?: "warmup failed")
            }
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            modelTaskScheduler.refreshModels()
            _uiState.update { it.copy(modelActionMessage = "model list refreshed") }
        }
    }

    fun switchModel(path: String) {
        viewModelScope.launch {
            runCatching { modelTaskScheduler.switchModel(path) }
                .onSuccess {
                    _uiState.update { state -> state.copy(modelActionMessage = "switched to: $path") }
                }
                .onFailure {
                    val message = it.message ?: "switch failed"
                    _uiState.update { state -> state.copy(modelActionMessage = message) }
                    showError(message)
                }
        }
    }

    fun importModelFromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(modelActionMessage = "importing model...") }
            runCatching {
                val imported = modelTaskScheduler.importModel(uri)
                runCatching { modelTaskScheduler.switchModel(imported.path) }
                    .fold(
                        onSuccess = { imported to null },
                        onFailure = { imported to (it.message ?: "activation failed") },
                    )
            }.onSuccess { (imported, activationError) ->
                val message = if (activationError == null) {
                    "model imported and activated: ${imported.name}"
                } else {
                    "model imported: ${imported.name}; activation failed: $activationError"
                }
                _uiState.update { state -> state.copy(modelActionMessage = message) }
                if (activationError == null) {
                    showNotice(message)
                } else {
                    showError(message)
                }
            }.onFailure {
                val message = it.message ?: "model import failed"
                _uiState.update { state -> state.copy(modelActionMessage = message) }
                showError(message)
            }
        }
    }

    fun startModelDownload(
        modelName: String = appConfig.modelDownload.fileName,
        primaryUrl: String = appConfig.modelDownload.primaryUrl,
        mirrorUrls: List<String> = appConfig.modelDownload.mirrorUrls,
        expectedSha256: String? = appConfig.modelDownload.expectedSha256,
        maxRetriesPerSource: Int = appConfig.modelDownload.maxRetriesPerSource,
    ) {
        val missingFields = buildList {
            if (modelName.isBlank()) add("runtime file name")
            if (primaryUrl.isBlank()) add("source url")
            if (expectedSha256.isNullOrBlank()) add("sha256")
        }
        if (missingFields.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    modelActionMessage = "download config incomplete: set ${missingFields.joinToString(", ")} first",
                )
            }
            showError("download config incomplete: set ${missingFields.joinToString(", ")} first")
            return
        }
        downloadJob?.cancel()
        val workId = modelTaskScheduler.enqueueDownload(
            modelName = modelName,
            primaryUrl = primaryUrl,
            mirrorUrls = mirrorUrls,
            expectedSha256 = expectedSha256,
            maxRetriesPerSource = maxRetriesPerSource,
        )
        _uiState.update {
            it.copy(
                downloadWorkId = workId.toString(),
                downloadState = "ENQUEUED",
                downloadProgressText = "0 / ?",
                downloadErrorCode = null,
                downloadErrorText = null,
                downloadSpeedText = null,
                downloadEtaText = null,
            )
        }
        lastDownloadBytes = 0L
        lastDownloadTs = System.currentTimeMillis()
        observeDownload(workId)
    }

    private fun observeDownload(workId: UUID) {
        downloadJob = viewModelScope.launch {
            modelTaskScheduler.observeDownloadWork(workId).collectLatest { state ->
                val now = System.currentTimeMillis()
                val dt = max(1L, now - lastDownloadTs)
                val dBytes = (state.downloadedBytes - lastDownloadBytes).coerceAtLeast(0L)
                val speedBytesPerSec = dBytes * 1000.0 / dt
                val remaining = if (state.totalBytes > 0L) (state.totalBytes - state.downloadedBytes).coerceAtLeast(0L) else -1L
                val etaSeconds = if (remaining >= 0L && speedBytesPerSec > 1.0) (remaining / speedBytesPerSec).toLong() else -1L
                lastDownloadBytes = state.downloadedBytes
                lastDownloadTs = now
                _uiState.update {
                    it.copy(
                        downloadState = state.state.name,
                        downloadProgressText = "${state.downloadedBytes} / ${state.totalBytes}",
                        downloadErrorCode = state.errorCode,
                        downloadErrorText = state.errorCode?.let(::downloadErrorMessage),
                        downloadSpeedText = humanReadableRate(speedBytesPerSec),
                        downloadEtaText = if (etaSeconds >= 0L) "${etaSeconds}s" else null,
                        modelActionMessage = state.message ?: it.modelActionMessage,
                    )
                }
                if (state.state.name in setOf("SUCCEEDED", "FAILED", "CANCELLED")) {
                    modelTaskScheduler.refreshModels()
                }
            }
        }
    }

    private suspend fun loadHistoryPage(page: Int) {
        val safePage = page.coerceAtLeast(0)
        val query = _uiState.value.historyAppliedQuery
        val selectedTag = _uiState.value.historySelectedTag
        _uiState.update { it.copy(historyLoading = true, historyErrorMessage = null) }
        runCatching {
            noteRepository.getHistoryPage(
                query = query,
                tag = selectedTag,
                page = safePage,
                pageSize = HISTORY_PAGE_SIZE + 1,
            )
        }.onSuccess { notes ->
            val hasNext = notes.size > HISTORY_PAGE_SIZE
            _uiState.update {
                it.copy(
                    history = notes.take(HISTORY_PAGE_SIZE).map(::toNoteRecordUi),
                    historyPageIndex = safePage,
                    historyHasNextPage = hasNext,
                    historyLoading = false,
                    historyErrorMessage = null,
                )
            }
        }.onFailure {
            _uiState.update { state ->
                state.copy(
                    historyLoading = false,
                    historyErrorMessage = it.message ?: "history load failed",
                )
            }
        }
    }

    override fun onCleared() {
        inferenceJob?.cancel()
        downloadJob?.cancel()
        viewModelScope.launch { orchestrator.shutdown() }
        super.onCleared()
    }
}

private fun humanReadableRate(bytesPerSec: Double): String {
    if (bytesPerSec <= 0.0) return "0 B/s"
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        bytesPerSec >= mb -> String.format("%.2f MB/s", bytesPerSec / mb)
        bytesPerSec >= kb -> String.format("%.1f KB/s", bytesPerSec / kb)
        else -> String.format("%.0f B/s", bytesPerSec)
    }
}

private fun downloadErrorMessage(code: String): String {
    return when (code) {
        "CHECKSUM_MISMATCH" -> "模型校验失败，请检查镜像文件一致性"
        "FINALIZE_FAILED" -> "模型文件落盘失败，请检查存储权限和磁盘空间"
        "HTTP_STATUS" -> "下载源返回异常状态码"
        "INVALID_INPUT" -> "下载配置无效（URL/重试参数）"
        "NETWORK_IO" -> "网络不可达或超时"
        "FILE_IO" -> "文件读写失败（空间不足或权限问题）"
        "NO_WORK_INFO" -> "任务状态不可用"
        "ALL_SOURCES_FAILED" -> "主源与镜像源均失败"
        else -> "未知下载错误"
    }
}

internal fun parseInferenceSettingsDraft(
    maxTokensText: String,
    temperatureText: String,
    topPText: String,
): InferenceSettings {
    val maxTokens = maxTokensText.trim().toIntOrNull()
        ?: throw IllegalArgumentException("max tokens must be an integer")
    val temperature = temperatureText.trim().toDoubleOrNull()
        ?: throw IllegalArgumentException("temperature must be a number")
    val topP = topPText.trim().toDoubleOrNull()
        ?: throw IllegalArgumentException("topP must be a number")
    require(maxTokens > 0) { "max tokens must be > 0" }
    require(temperature >= 0.0) { "temperature must be >= 0" }
    require(topP in 0.0..1.0) { "topP must be in [0,1]" }
    return InferenceSettings(
        maxTokens = maxTokens,
        temperature = temperature,
        topP = topP,
    )
}

private fun toNoteRecordUi(item: com.example.rwkvnotes.data.local.NoteWithTags): NoteRecordUi {
    return NoteRecordUi(
        id = item.note.id,
        markdown = item.note.markdown,
        tags = item.tags.map { tag -> tag.tag },
    )
}

private const val HISTORY_PAGE_SIZE = 10
