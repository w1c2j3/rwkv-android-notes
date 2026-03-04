package com.example.rwkvnotes.obs

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class InferenceMetrics(
    val ttftMs: Long = 0L,
    val tokensPerSecond: Double = 0.0,
    val cacheHit: Boolean = false,
    val totalDurationMs: Long = 0L,
)

@Singleton
class InferenceMetricsService @Inject constructor() {
    private val _metrics = MutableStateFlow(InferenceMetrics())
    val metrics: StateFlow<InferenceMetrics> = _metrics.asStateFlow()

    fun reset() {
        _metrics.value = InferenceMetrics()
    }

    fun update(ttftMs: Long, tokensPerSecond: Double, cacheHit: Boolean, totalDurationMs: Long) {
        _metrics.value = InferenceMetrics(
            ttftMs = ttftMs,
            tokensPerSecond = tokensPerSecond,
            cacheHit = cacheHit,
            totalDurationMs = totalDurationMs,
        )
    }
}
