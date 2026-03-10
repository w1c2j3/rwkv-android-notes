package com.example.rwkvnotes.config

import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    val model: ModelConfig,
    val modelDownload: ModelDownloadConfig,
    val inferEquation: InferEquationConfig,
    val inferSampling: InferSamplingConfig,
    val prompt: PromptConfig,
    val tagging: TaggingConfig,
    val cache: CacheConfig,
)

@Serializable
data class ModelConfig(
    val path: String,
    val runtimeExtension: String,
    val maxTokens: Int,
    val contextWindowTokens: Int,
    val temperature: Double,
    val topP: Double,
)

@Serializable
data class ModelDownloadConfig(
    val fileName: String,
    val primaryUrl: String,
    val mirrorUrls: List<String>,
    val expectedSha256: String?,
    val maxRetriesPerSource: Int,
)

@Serializable
data class InferEquationConfig(
    val hDecay: Double,
    val xMix: Double,
    val oMix: Double,
    val attBaseDecay: Double,
    val attDecayScale: Double,
    val windowSize: Int,
    val projFanIn: Int,
)

@Serializable
data class InferSamplingConfig(
    val topK: Int,
    val repeatPenalty: Double,
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
