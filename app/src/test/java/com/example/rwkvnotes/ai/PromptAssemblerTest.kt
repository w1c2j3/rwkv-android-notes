package com.example.rwkvnotes.ai

import com.example.rwkvnotes.config.PromptConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerTest {
    @Test
    fun compose_uses_strict_template_and_includes_input() {
        val assembler = PromptAssembler()
        val prompt = assembler.compose(
            promptConfig = PromptConfig(
                system = "system prompt",
                bossDataSnippet = "glossary terms",
            ),
            userText = "raw note",
            contextWindowTokens = 2048,
        )

        assertTrue(prompt.contains("Instruction: system prompt"))
        assertTrue(prompt.contains("glossary terms"))
        assertTrue(prompt.contains("raw note"))
        assertTrue(prompt.contains("Tags: tag1, tag2, tag3"))
    }

    @Test
    fun compose_truncates_middle_when_text_too_long() {
        val assembler = PromptAssembler()
        val longText = buildString {
            repeat(5000) { append("A") }
            append("END")
        }
        val prompt = assembler.compose(
            promptConfig = PromptConfig(
                system = "unused",
                bossDataSnippet = "glossary terms",
            ),
            userText = longText,
            contextWindowTokens = 512,
        )

        assertTrue(prompt.contains("...[TRUNCATED_MIDDLE]..."))
        assertTrue(prompt.contains("END"))
    }
}
