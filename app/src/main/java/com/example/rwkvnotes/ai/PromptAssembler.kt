package com.example.rwkvnotes.ai

import com.example.rwkvnotes.config.PromptConfig

class PromptAssembler {
    fun compose(
        promptConfig: PromptConfig,
        userText: String,
        contextWindowTokens: Int,
    ): String {
        val systemPrompt = promptConfig.system.trim().ifBlank {
            "You are a CS student assistant. Restructure the input into Markdown and append a final Tags line."
        }
        val glossary = promptConfig.bossDataSnippet.trim()
        val trimmedInput = userText.trim()
        val contextCharsBudget = (contextWindowTokens.coerceAtLeast(256)) * 3
        val fixedTemplateChars = 256 + glossary.length
        val inputCharsBudget = (contextCharsBudget - fixedTemplateChars).coerceAtLeast(128)
        val inputForPrompt = if (trimmedInput.length <= inputCharsBudget) {
            trimmedInput
        } else {
            val head = inputCharsBudget / 2
            val tail = inputCharsBudget - head
            val prefix = trimmedInput.take(head)
            val suffix = trimmedInput.takeLast(tail)
            "$prefix\n...[TRUNCATED_MIDDLE]...\n$suffix"
        }
        return """
        Instruction: $systemPrompt
        Glossary to consider: $glossary
        
        Input:
        $inputForPrompt
        Output rules:
        1. Return Markdown only for the main body.
        2. Put tags on the last line using `Tags: tag1, tag2, tag3`.
        """.trimIndent()
    }
}
