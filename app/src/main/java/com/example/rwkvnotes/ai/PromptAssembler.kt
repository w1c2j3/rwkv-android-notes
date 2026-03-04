package com.example.rwkvnotes.ai

import com.example.rwkvnotes.config.PromptConfig

class PromptAssembler {
    fun compose(
        promptConfig: PromptConfig,
        userText: String,
        contextWindowTokens: Int,
    ): String {
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
        Instruction: You are a CS student assistant. Restructure the input text into a Markdown format with hierarchical headings, bullet points, and extract 3-5 relevant tags at the end.
        Glossary to consider: $glossary
        
        Input:
        $inputForPrompt
        Output (in strict Markdown):
        """.trimIndent()
    }
}
