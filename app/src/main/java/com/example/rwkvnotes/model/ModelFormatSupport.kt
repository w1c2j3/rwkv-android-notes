package com.example.rwkvnotes.model

internal fun normalizeRuntimeExtension(extension: String): String {
    val trimmed = extension.trim()
    if (trimmed.isBlank()) return ".bin"
    return if (trimmed.startsWith(".")) trimmed.lowercase() else ".${trimmed.lowercase()}"
}

internal fun matchesRuntimeExtension(fileName: String, requiredExtension: String): Boolean {
    val normalized = normalizeRuntimeExtension(requiredExtension)
    return fileName.trim().lowercase().endsWith(normalized)
}
