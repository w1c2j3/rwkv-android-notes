package com.example.rwkvnotes.config

import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    val model: ModelConfig,
    val prompt: PromptConfig,
    val tagging: TaggingConfig,
    val cache: CacheConfig,
)

@Serializable
data class ModelConfig(
    val path: String,
    val maxTokens: Int,
    val contextWindowTokens: Int,
    val temperature: Double,
    val topP: Double,
)

@Serializable
data class PromptConfig(
    val system: String,
    val bossDataSnippet: String,
)

@Serializable
data class TaggingConfig(
    val maxTags: Int,
    val defaultTags: List<String>,
)

@Serializable
data class CacheConfig(
    val maxEntries: Int,
)
