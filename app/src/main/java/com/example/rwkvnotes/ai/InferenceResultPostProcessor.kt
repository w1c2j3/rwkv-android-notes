package com.example.rwkvnotes.ai

import com.example.rwkvnotes.ai.protocol.InferenceFinalJson
import com.example.rwkvnotes.config.TaggingConfig

class InferenceResultPostProcessor {
    fun normalize(result: InferenceFinalJson, taggingConfig: TaggingConfig): InferenceFinalJson {
        val rawText = result.raw.normalizeContent().ifBlank { result.markdown.normalizeContent() }
        val mergedTags = sanitizeTags(result.tags, taggingConfig.maxTags)
        if (rawText.isBlank()) {
            val fallbackTags = if (mergedTags.isNotEmpty()) {
                mergedTags
            } else {
                sanitizeTags(taggingConfig.defaultTags, taggingConfig.maxTags)
            }
            return result.copy(
                markdown = result.markdown.normalizeContent(),
                tags = fallbackTags,
                raw = "",
            )
        }

        val extraction = extractTags(rawText)
        val tagList = sanitizeTags(extraction.tags + mergedTags, taggingConfig.maxTags).ifEmpty {
            sanitizeTags(taggingConfig.defaultTags, taggingConfig.maxTags)
        }
        val body = extraction.markdownBody.ifBlank { rawText }
        val markdown = if (looksLikeMarkdown(body)) body else toMarkdown(body)
        return InferenceFinalJson(
            markdown = markdown,
            tags = tagList,
            raw = rawText,
        )
    }

    private fun extractTags(text: String): TagExtraction {
        val keptLines = mutableListOf<String>()
        val tags = mutableListOf<String>()
        val lines = text.lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed.isBlank()) {
                keptLines += line
                index += 1
                continue
            }
            if (trimmed.matches(HASHTAG_LINE_REGEX)) {
                tags += HASHTAG_REGEX.findAll(trimmed).map { it.groupValues[1] }.toList()
                index += 1
                continue
            }
            val inlineTagMatch = INLINE_TAG_LINE_REGEX.matchEntire(trimmed)
            if (inlineTagMatch != null) {
                tags += splitInlineTags(inlineTagMatch.groupValues[2])
                index += 1
                continue
            }
            if (trimmed.matches(TAG_HEADING_REGEX)) {
                index += 1
                while (index < lines.size) {
                    val item = lines[index].trim()
                    if (item.isBlank()) {
                        index += 1
                        break
                    }
                    val bulletText = item.removeBulletPrefix() ?: break
                    tags += listOf(bulletText)
                    index += 1
                }
                continue
            }
            keptLines += line
            index += 1
        }
        return TagExtraction(
            markdownBody = keptLines.joinToString("\n").normalizeContent(),
            tags = tags,
        )
    }

    private fun splitInlineTags(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val hashtags = HASHTAG_REGEX.findAll(text).map { it.groupValues[1] }.toList()
        if (hashtags.isNotEmpty()) return hashtags
        return text
            .split(INLINE_TAG_SEPARATOR_REGEX)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun sanitizeTags(tags: List<String>, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val seen = linkedSetOf<String>()
        val cleaned = mutableListOf<String>()
        tags.forEach { rawTag ->
            val tag = rawTag
                .trim()
                .trim('#')
                .removePrefix("-")
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(40)
            if (tag.isBlank()) return@forEach
            val key = tag.lowercase()
            if (seen.add(key)) {
                cleaned += tag
            }
        }
        return cleaned.take(limit)
    }

    private fun looksLikeMarkdown(text: String): Boolean {
        return text.lineSequence().any { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("#") ||
                trimmed.startsWith("- ") ||
                trimmed.startsWith("* ") ||
                ORDERED_LIST_REGEX.containsMatchIn(trimmed) ||
                trimmed.startsWith("> ") ||
                trimmed.startsWith("```")
        }
    }

    private fun toMarkdown(text: String): String {
        val lines = text
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""
        if (lines.size == 1) {
            return "## Notes\n\n${lines.first()}"
        }
        return buildString {
            append("## Notes\n\n")
            lines.forEach { line ->
                append("- ")
                append(line)
                append('\n')
            }
        }.trimEnd()
    }

    private fun String.normalizeContent(): String {
        return replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    private fun String.removeBulletPrefix(): String? {
        return when {
            startsWith("- ") -> removePrefix("- ").trim()
            startsWith("* ") -> removePrefix("* ").trim()
            ORDERED_LIST_REGEX.containsMatchIn(this) -> replaceFirst(Regex("^\\d+[.)]\\s+"), "").trim()
            else -> null
        }
    }

    private data class TagExtraction(
        val markdownBody: String,
        val tags: List<String>,
    )

    private companion object {
        val INLINE_TAG_LINE_REGEX = Regex("""(?i)^(tags?|labels?|keywords?|标签)\s*[:：]\s*(.+)$""")
        val TAG_HEADING_REGEX = Regex("""(?i)^#{1,6}\s*(tags?|labels?|keywords?|标签)\s*$""")
        val HASHTAG_LINE_REGEX = Regex("""^(?:#([\p{L}\p{N}_-]{2,32})\s*)+$""")
        val HASHTAG_REGEX = Regex("""#([\p{L}\p{N}_-]{2,32})""")
        val INLINE_TAG_SEPARATOR_REGEX = Regex("""\s*[,，;；|/]\s*""")
        val ORDERED_LIST_REGEX = Regex("""^\d+[.)]\s+""")
    }
}
