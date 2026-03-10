package com.example.rwkvnotes.ai

import com.example.rwkvnotes.ai.protocol.InferenceFinalJson
import com.example.rwkvnotes.config.TaggingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceResultPostProcessorTest {
    private val processor = InferenceResultPostProcessor()
    private val taggingConfig = TaggingConfig(
        maxTags = 4,
        defaultTags = listOf("notes", "rwkv"),
    )

    @Test
    fun normalize_extracts_inline_tags_and_keeps_markdown_body() {
        val result = processor.normalize(
            InferenceFinalJson(
                markdown = "",
                tags = emptyList(),
                raw = """
                    ## Summary
                    - Native result is now real
                    - Final payload comes from generated text
                    Tags: rwkv, android, local-ai
                """.trimIndent(),
            ),
            taggingConfig = taggingConfig,
        )

        assertTrue(result.markdown.contains("## Summary"))
        assertTrue(!result.markdown.contains("Tags:"))
        assertEquals(listOf("rwkv", "android", "local-ai"), result.tags)
    }

    @Test
    fun normalize_converts_plain_text_into_markdown() {
        val result = processor.normalize(
            InferenceFinalJson(
                markdown = "",
                tags = emptyList(),
                raw = "first line\nsecond line",
            ),
            taggingConfig = taggingConfig,
        )

        assertTrue(result.markdown.startsWith("## Notes"))
        assertTrue(result.markdown.contains("- first line"))
        assertTrue(result.markdown.contains("- second line"))
        assertEquals(listOf("notes", "rwkv"), result.tags)
    }

    @Test
    fun normalize_merges_existing_tags_and_limits_count() {
        val result = processor.normalize(
            InferenceFinalJson(
                markdown = "",
                tags = listOf("android", "rwkv"),
                raw = """
                    # Tags
                    - Android
                    - JNI
                    - Streaming
                    - Room
                    body stays here
                """.trimIndent(),
            ),
            taggingConfig = taggingConfig,
        )

        assertEquals(listOf("Android", "JNI", "Streaming", "Room"), result.tags)
        assertTrue(result.markdown.startsWith("## Notes"))
    }
}
