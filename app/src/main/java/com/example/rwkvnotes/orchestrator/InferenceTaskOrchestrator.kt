package com.example.rwkvnotes.orchestrator

import android.net.Uri
import com.example.rwkvnotes.ai.AiProcessor
import com.example.rwkvnotes.ai.protocol.InferenceEvent
import com.example.rwkvnotes.ingest.DocumentIngestionPipeline
import com.example.rwkvnotes.obs.InferenceMetricsService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach

sealed interface OrchestratorEvent {
    data class Started(val source: String, val rawInput: String) : OrchestratorEvent
    data class Model(val event: InferenceEvent) : OrchestratorEvent
    data class Failed(val message: String) : OrchestratorEvent
}

@Singleton
class InferenceTaskOrchestrator @Inject constructor(
    private val aiProcessor: AiProcessor,
    private val ingestion: DocumentIngestionPipeline,
    val metricsService: InferenceMetricsService,
) {
    fun runFromText(text: String): Flow<OrchestratorEvent> = flow {
        val startedAt = System.currentTimeMillis()
        metricsService.reset()
        emit(OrchestratorEvent.Started(source = "text", rawInput = text))
        var firstTokenAt = 0L
        var tokenCount = 0
        aiProcessor.streamInference(text).onEach { event ->
            when (event) {
                is InferenceEvent.Token -> {
                    tokenCount += 1
                    if (firstTokenAt == 0L) {
                        firstTokenAt = System.currentTimeMillis()
                    }
                }
                is InferenceEvent.Completed -> {
                    val finishedAt = System.currentTimeMillis()
                    val ttft = if (firstTokenAt == 0L) 0L else firstTokenAt - startedAt
                    val duration = (finishedAt - startedAt).coerceAtLeast(1L)
                    val tps = if (tokenCount == 0) 0.0 else tokenCount * 1000.0 / duration
                    metricsService.update(
                        ttftMs = ttft,
                        tokensPerSecond = tps,
                        cacheHit = tokenCount == 0,
                        totalDurationMs = duration,
                    )
                }
                is InferenceEvent.Failed -> Unit
            }
            emit(OrchestratorEvent.Model(event))
        }
    }

    fun runFromUri(uri: Uri): Flow<OrchestratorEvent> = flow {
        emit(OrchestratorEvent.Started(source = "file", rawInput = ""))
        val ingested = ingestion.ingest(uri).getOrElse {
            emit(OrchestratorEvent.Failed(it.message ?: "failed to parse file"))
            return@flow
        }
        emit(OrchestratorEvent.Started(source = "file", rawInput = ingested.plainText))
        runFromText(ingested.plainText).collect { event ->
            if (event is OrchestratorEvent.Started) return@collect
            emit(event)
        }
    }

    suspend fun cancel() {
        aiProcessor.cancel()
    }

    suspend fun shutdown() {
        aiProcessor.shutdown()
    }
}
