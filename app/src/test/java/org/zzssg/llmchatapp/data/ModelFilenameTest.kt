package org.zzssg.llmchatapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFilenameTest {

    @Test
    fun `a long model name keeps its gguf extension`() {
        val sanitized = sanitizeModelFilename("a".repeat(200) + ".gguf")

        assertEquals(120, sanitized.length)
        assertTrue(sanitized.endsWith(".gguf"))
    }

    @Test
    fun `path components and unsafe characters are removed`() {
        assertEquals("my_model_.gguf", sanitizeModelFilename("folder\\my model?.GGUF"))
    }

    @Test
    fun `an empty stem gets a usable fallback`() {
        assertEquals("model.gguf", sanitizeModelFilename(".gguf"))
    }
}
