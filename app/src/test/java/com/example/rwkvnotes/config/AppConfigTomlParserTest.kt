package com.example.rwkvnotes.config

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigTomlParserTest {
    @Test
    fun parseTomlText_parses_sections_and_keys() {
        val toml = """
            [model]
            path = "/tmp/model.bin"
            runtime_extension = ".bin"
            max_tokens = 128
            
            [prompt]
            system = "sys"
        """.trimIndent()

        val result = parseTomlText(toml)

        assertEquals("/tmp/model.bin", result["model"]?.get("path"))
        assertEquals(".bin", result["model"]?.get("runtime_extension"))
        assertEquals("128", result["model"]?.get("max_tokens"))
        assertEquals("sys", result["prompt"]?.get("system"))
    }
}
