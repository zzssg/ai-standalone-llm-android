package org.zzssg.llmchatapp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningParserTest {

    @Test
    fun `text without think tags is untouched`() {
        val blocks = splitReasoning("Just an answer.")
        assertEquals(listOf(MarkdownBlock.Paragraph("Just an answer.")), blocks)
    }

    @Test
    fun `a closed think block is separated from the answer`() {
        val blocks = splitReasoning("<think>weighing options</think>\n\nBlue")

        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlock.Reasoning("weighing options", complete = true), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("Blue"), blocks[1])
    }

    /** While tokens stream in, the closing tag has not arrived yet. */
    @Test
    fun `an unterminated think block is marked incomplete`() {
        val blocks = splitReasoning("<think>still reasoning")

        val reasoning = blocks.single() as MarkdownBlock.Reasoning
        assertEquals("still reasoning", reasoning.text)
        assertTrue("should be marked incomplete", !reasoning.complete)
    }

    /**
     * The observed qwen35 output: an empty think block followed by the answer.
     * It must not swallow the answer.
     */
    @Test
    fun `an empty think block still yields the answer`() {
        val blocks = splitReasoning("<think>\n\n</think>\n\nBlue")

        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlock.Reasoning("", complete = true), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("Blue"), blocks[1])
    }

    @Test
    fun `prose before a think block is preserved`() {
        val blocks = splitReasoning("Sure. <think>hmm</think> Blue.")

        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Paragraph("Sure."), blocks[0])
        assertEquals(MarkdownBlock.Reasoning("hmm", complete = true), blocks[1])
        assertEquals(MarkdownBlock.Paragraph("Blue."), blocks[2])
    }

    @Test
    fun `code fences inside the answer still parse`() {
        val blocks = splitReasoning("<think>plan</think>\n\nHere:\n```kotlin\nval x = 1\n```")

        assertTrue(blocks[0] is MarkdownBlock.Reasoning)
        assertTrue("expected a code block, got $blocks", blocks.any { it is MarkdownBlock.Code })
    }
}
