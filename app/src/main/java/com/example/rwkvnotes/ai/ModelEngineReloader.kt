package com.example.rwkvnotes.ai

interface ModelEngineReloader {
    suspend fun reloadEngine(modelPath: String): Boolean
}
