package com.example.rwkvnotes.model

internal fun normalizeImportedModelFileName(
    candidateName: String?,
    requiredRuntimeExtension: String,
    fallbackBaseName: String = "imported-model",
): String {
    val normalizedExtension = normalizeRuntimeExtension(requiredRuntimeExtension)
    val rawName = candidateName
        ?.replace('\\', '/')
        ?.substringAfterLast('/')
        ?.substringAfterLast(':')
        ?.trim()
        .orEmpty()
    val sanitized = rawName
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), "_")
        .trim('_')
    val baseName = sanitized.ifBlank { fallbackBaseName }
    if (baseName.endsWith(normalizedExtension, ignoreCase = true)) {
        return baseName.dropLast(normalizedExtension.length) + normalizedExtension
    }
    val stem = baseName.substringBeforeLast('.', missingDelimiterValue = baseName).ifBlank { fallbackBaseName }
    return "$stem$normalizedExtension"
}
