package com.example.rwkvnotes.ai

import com.example.rwkvnotes.ai.protocol.InferenceEvent
import kotlinx.coroutines.flow.Flow

interface AiProcessor {
    fun streamInference(userText: String): Flow<InferenceEvent>
    suspend fun cancel()
    suspend fun shutdown()
}
