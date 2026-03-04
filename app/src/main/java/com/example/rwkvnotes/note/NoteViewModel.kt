package com.example.rwkvnotes.note

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.rwkvnotes.ai.protocol.InferenceEvent
import com.example.rwkvnotes.model.ModelDescriptor
import com.example.rwkvnotes.model.ModelTaskScheduler
import com.example.rwkvnotes.obs.InferenceMetrics
import com.example.rwkvnotes.orchestrator.InferenceTaskOrchestrator
import com.example.rwkvnotes.orchestrator.OrchestratorEvent
import com.example.rwkvnotes.index.SemanticIndexService
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
    val history: List<NoteRecordUi> = emptyList(),
    val currentScreen: AppScreen = AppScreen.HOME,
    val metrics: InferenceMetrics = InferenceMetrics(),
    val activeModelPath: String = "",
    val mmapReadable: Boolean = false,
    val lastWarmupSuccess: Boolean = false,
    val models: List<ModelDescriptor> = emptyList(),
    val modelActionMessage: String? = null,
)

@HiltViewModel
class NoteViewModel @Inject constructor(
    private val orchestrator: InferenceTaskOrchestrator,
    private val noteRepository: NoteRepository,
    private val modelTaskScheduler: ModelTaskScheduler,
    private val semanticIndexService: SemanticIndexService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NoteUiState())
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()
    private var inferenceJob: Job? = null
    private val streamingBuffer = StringBuilder()
    private var runningRawInput: String = ""

    init {
        viewModelScope.launch {
            noteRepository.observeNotes().collectLatest { notes ->
                semanticIndexService.rebuild(notes)
                _uiState.update {
                    it.copy(
                        history = notes.map { n ->
                            NoteRecordUi(
                                id = n.note.id,
                                markdown = n.note.markdown,
                                tags = n.tags.map { tag -> tag.tag },
                            )
                        },
                    )
                }
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
    }

    fun onInputChanged(value: String) {
        _uiState.update { it.copy(inputText = value) }
    }

    fun runRestructure() {
        val input = _uiState.value.inputText.trim()
        if (input.isBlank()) {
            _uiState.update { it.copy(errorMessage = "input text is blank") }
            throw IllegalArgumentException("input text is blank")
        }
        runFromText(input)
    }

    fun runFromUri(uri: Uri) {
        inferenceJob?.cancel()
        inferenceJob = viewModelScope.launch {
            orchestrator.cancel()
            streamingBuffer.clear()
            runningRawInput = ""
            _uiState.update {
                it.copy(
                    isRunning = true,
                    errorMessage = null,
                    streamingText = "",
                    lastMarkdown = "",
                    lastTags = emptyList(),
                    currentScreen = AppScreen.STREAM,
                )
            }
            orchestrator.runFromUri(uri).collectLatest { event ->
                handleOrchestratorEvent(event, "")
            }
            inferenceJob = null
        }
    }

    private fun runFromText(input: String) {
        inferenceJob?.cancel()
        inferenceJob = viewModelScope.launch {
            orchestrator.cancel()
            streamingBuffer.clear()
            runningRawInput = input
            _uiState.update {
                it.copy(
                    isRunning = true,
                    errorMessage = null,
                    streamingText = "",
                    lastMarkdown = "",
                    lastTags = emptyList(),
                    currentScreen = AppScreen.STREAM,
                )
            }
            orchestrator.runFromText(input).collectLatest { event ->
                handleOrchestratorEvent(event, input)
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
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        errorMessage = event.message,
                    )
                }
                throw IllegalStateException(event.message)
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
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            errorMessage = "${modelEvent.error.code}: ${modelEvent.error.message}",
                        )
                    }
                    throw IllegalStateException("${modelEvent.error.code}: ${modelEvent.error.message}")
                }
            }
        }
    }

    fun navigate(screen: AppScreen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun stopInference() {
        inferenceJob?.cancel()
        viewModelScope.launch { orchestrator.cancel() }
        _uiState.update { it.copy(isRunning = false) }
    }

    fun warmupModel() {
        viewModelScope.launch {
            val ok = modelTaskScheduler.warmupModel()
            _uiState.update { it.copy(modelActionMessage = if (ok) "warmup success" else "warmup failed") }
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
                    _uiState.update { state -> state.copy(modelActionMessage = it.message ?: "switch failed") }
                    throw it
                }
        }
    }

    override fun onCleared() {
        inferenceJob?.cancel()
        viewModelScope.launch { orchestrator.shutdown() }
        super.onCleared()
    }
}
