package org.zzssg.llmchatapp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    // splitReasoning is the entry point; with no <think> tags present it
    // delegates straight to the prose/fence parser these cases exercise.

    @Test
    fun `plain text becomes a single paragraph`() {
        val blocks = splitReasoning("Hello there.")

        assertEquals(1, blocks.size)
        assertEquals(MarkdownBlock.Paragraph("Hello there."), blocks[0])
    }

    @Test
    fun `empty input produces no blocks`() {
        assertTrue(splitReasoning("").isEmpty())
    }

    @Test
    fun `fenced block is separated from surrounding prose`() {
        val blocks = splitReasoning(
            """
            Here is how:
            ```kotlin
            fun main() {
                println("hi")
            }
            ```
            That's it.
            """.trimIndent()
        )

        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Paragraph("Here is how:"), blocks[0])

        val code = blocks[1] as MarkdownBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("fun main() {\n    println(\"hi\")\n}", code.code)

        assertEquals(MarkdownBlock.Paragraph("That's it."), blocks[2])
    }

    @Test
    fun `fence without a language tag still parses`() {
        val blocks = splitReasoning("```\nplain\n```")

        val code = blocks.single() as MarkdownBlock.Code
        assertEquals("", code.language)
        assertEquals("plain", code.code)
    }

    /**
     * While a reply streams in, the closing fence has not arrived yet. Treating
     * the open fence as a code block keeps the bubble from flipping between
     * plain and formatted on every token.
     */
    @Test
    fun `unterminated fence is treated as a still-streaming code block`() {
        val blocks = splitReasoning("Try:\n```python\nprint(1)")

        assertEquals(2, blocks.size)
        val code = blocks[1] as MarkdownBlock.Code
        assertEquals("python", code.language)
        assertEquals("print(1)", code.code)
    }

    @Test
    fun `consecutive fences do not merge`() {
        val blocks = splitReasoning("```\na\n```\n```\nb\n```")

        assertEquals(2, blocks.size)
        assertEquals("a", (blocks[0] as MarkdownBlock.Code).code)
        assertEquals("b", (blocks[1] as MarkdownBlock.Code).code)
    }

    @Test
    fun `blank lines between paragraphs do not create empty blocks`() {
        val blocks = splitReasoning("First\n\n```\ncode\n```\n\n\nSecond")

        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Paragraph("First"), blocks[0])
        assertTrue(blocks[1] is MarkdownBlock.Code)
        assertEquals(MarkdownBlock.Paragraph("Second"), blocks[2])
    }
}
