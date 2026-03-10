package com.example.rwkvnotes.config

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppConfigLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun load(): AppConfig {
        val text = context.assets.open("config/app_config.toml").bufferedReader().use { it.readText() }
        val parsed = parseTomlText(text)
        val model = parsed["model"].orEmpty()
        val modelDownload = parsed["model_download"].orEmpty()
        val inferEquation = parsed["infer_equation"].orEmpty()
        val inferSampling = parsed["infer_sampling"].orEmpty()
        val prompt = parsed["prompt"].orEmpty()
        val tagging = parsed["tagging"].orEmpty()
        val cache = parsed["cache"].orEmpty()
        return AppConfig(
            model = ModelConfig(
                path = model["path"].orEmpty(),
                runtimeExtension = model["runtime_extension"].orEmpty().ifBlank { ".bin" },
                maxTokens = model["max_tokens"]?.toIntOrNull() ?: 256,
                contextWindowTokens = model["context_window_tokens"]?.toIntOrNull() ?: 2048,
                temperature = model["temperature"]?.toDoubleOrNull() ?: 0.7,
                topP = model["top_p"]?.toDoubleOrNull() ?: 0.9,
            ),
            modelDownload = ModelDownloadConfig(
                fileName = modelDownload["file_name"].orEmpty(),
                primaryUrl = modelDownload["primary_url"].orEmpty(),
                mirrorUrls = parseArray(modelDownload["mirror_urls"]),
                expectedSha256 = modelDownload["expected_sha256"]?.ifBlank { null },
                maxRetriesPerSource = modelDownload["max_retries_per_source"]?.toIntOrNull() ?: 3,
            ),
            inferEquation = InferEquationConfig(
                hDecay = inferEquation["h_decay"]?.toDoubleOrNull() ?: 0.62,
                xMix = inferEquation["x_mix"]?.toDoubleOrNull() ?: 0.18,
                oMix = inferEquation["o_mix"]?.toDoubleOrNull() ?: 0.20,
                attBaseDecay = inferEquation["att_base_decay"]?.toDoubleOrNull() ?: 0.92,
                attDecayScale = inferEquation["att_decay_scale"]?.toDoubleOrNull() ?: 0.07,
                windowSize = inferEquation["window_size"]?.toIntOrNull() ?: 192,
                projFanIn = inferEquation["proj_fan_in"]?.toIntOrNull() ?: 8,
            ),
            inferSampling = InferSamplingConfig(
                topK = inferSampling["top_k"]?.toIntOrNull() ?: 40,
                repeatPenalty = inferSampling["repeat_penalty"]?.toDoubleOrNull() ?: 1.10,
            ),
            prompt = PromptConfig(
                system = prompt["system"].orEmpty(),
                bossDataSnippet = prompt["boss_data_snippet"].orEmpty(),
            ),
            tagging = TaggingConfig(
                maxTags = tagging["max_tags"]?.toIntOrNull() ?: 8,
                defaultTags = parseArray(tagging["default_tags"]),
            ),
            cache = CacheConfig(
                maxEntries = cache["max_entries"]?.toIntOrNull() ?: 64,
            ),
        )
    }

    private fun parseArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw
            .removePrefix("[")
            .removeSuffix("]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() }
    }
}

internal fun parseTomlText(text: String): Map<String, Map<String, String>> {
    val sectionMap = linkedMapOf<String, MutableMap<String, String>>()
    var current = ""
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .forEach { line ->
            when {
                line.startsWith("[") && line.endsWith("]") -> {
                    current = line.removePrefix("[").removeSuffix("]")
                    sectionMap.putIfAbsent(current, linkedMapOf())
                }

                "=" in line -> {
                    val splitIndex = line.indexOf("=")
                    val key = line.substring(0, splitIndex).trim()
                    val value = line.substring(splitIndex + 1).trim().removeSurrounding("\"")
                    val target = sectionMap.getOrPut(current) { linkedMapOf() }
                    target[key] = value
                }
            }
        }
    return sectionMap
}
