package com.example.rwkvnotes.ai

data class EngineReloadResult(
    val success: Boolean,
    val errorMessage: String? = null,
)

interface ModelEngineReloader {
    suspend fun reloadEngine(modelPath: String): EngineReloadResult
}
