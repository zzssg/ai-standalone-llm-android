package org.zzssg.llmchatapp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {

    // -- reasoning ----------------------------------------------------------

    /**
     * The case seen on screen: thinking was switched on, so the template put the
     * opening tag in the *prompt* and the reply arrives already inside the block,
     * carrying only the closing tag. Waiting for `<think>` rendered the whole
     * scratchpad as the answer with a stray `</think>` in the middle of it.
     */
    @Test
    fun `a reply that starts inside a think block is still reasoning`() {
        val blocks = splitReasoning("The user wants help.\n</think>\n\nHere is the answer.")

        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlock.Reasoning("The user wants help.", complete = true), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("Here is the answer."), blocks[1])
    }

    @Test
    fun `a normal closed think block still works`() {
        val blocks = splitReasoning("<think>weighing options</think>\n\nBlue")

        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlock.Reasoning("weighing options", complete = true), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("Blue"), blocks[1])
    }

    @Test
    fun `an unterminated block is marked incomplete while it streams`() {
        val reasoning = splitReasoning("<think>still going").single() as MarkdownBlock.Reasoning

        assertEquals("still going", reasoning.text)
        assertTrue(!reasoning.complete)
    }

    @Test
    fun `text with no tags at all is untouched`() {
        assertEquals(listOf(MarkdownBlock.Paragraph("Just an answer.")), splitReasoning("Just an answer."))
    }

    // -- headings, rules ----------------------------------------------------

    @Test
    fun `headings are recognised at every level`() {
        val blocks = splitReasoning("# One\n## Two\n### Three")

        assertEquals(
            listOf(
                MarkdownBlock.Heading(1, "One"),
                MarkdownBlock.Heading(2, "Two"),
                MarkdownBlock.Heading(3, "Three"),
            ),
            blocks,
        )
    }

    @Test
    fun `a hash without a space is not a heading`() {
        assertEquals(listOf(MarkdownBlock.Paragraph("#hashtag")), splitReasoning("#hashtag"))
    }

    @Test
    fun `horizontal rules are recognised and not read as bullets`() {
        val blocks = splitReasoning("Above\n\n---\n\nBelow")

        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Rule, blocks[1])
    }

    // -- tables -------------------------------------------------------------

    @Test
    fun `a pipe table becomes a table`() {
        val blocks = splitReasoning(
            """
            | Type | Example |
            |---|---|
            | Total income | Person earns 50/hr |
            | Average income | 80/day |
            """.trimIndent()
        )

        val table = blocks.single() as MarkdownBlock.Table
        assertEquals(listOf("Type", "Example"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Total income", "Person earns 50/hr"), table.rows[0])
    }

    /** Models write pipes in prose; only a divider row makes it a table. */
    @Test
    fun `a sentence containing a pipe is not a table`() {
        val blocks = splitReasoning("Use grep | sort to chain them.")

        assertEquals(listOf(MarkdownBlock.Paragraph("Use grep | sort to chain them.")), blocks)
    }

    @Test
    fun `tables without outer pipes still parse`() {
        val table = splitReasoning("A | B\n--- | ---\n1 | 2").single() as MarkdownBlock.Table

        assertEquals(listOf("A", "B"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    // -- lists --------------------------------------------------------------

    @Test
    fun `bullet lists group consecutive items`() {
        val bullets = splitReasoning("- one\n- two\n- three").single() as MarkdownBlock.Bullets

        assertEquals(listOf("one", "two", "three"), bullets.items)
        assertTrue(!bullets.ordered)
    }

    @Test
    fun `ordered lists keep their starting number`() {
        val bullets = splitReasoning("3. three\n4. four").single() as MarkdownBlock.Bullets

        assertEquals(listOf("three", "four"), bullets.items)
        assertTrue(bullets.ordered)
        assertEquals(3, bullets.start)
    }

    // -- combinations -------------------------------------------------------

    @Test
    fun `the observed reply shape parses end to end`() {
        val blocks = splitReasoning(
            """
            The user wants help with income problems.
            </think>

            Here's how to solve them:

            ---

            ## 1. Identify What You're Given

            | Type of Problem | Example |
            |---|---|
            | Total income | Two jobs |
            """.trimIndent()
        )

        assertTrue("reasoning is split out", blocks[0] is MarkdownBlock.Reasoning)
        assertTrue("the answer survives", blocks.any { it is MarkdownBlock.Paragraph })
        assertTrue("the rule is a rule", blocks.any { it == MarkdownBlock.Rule })
        assertTrue("the heading is a heading", blocks.any { it is MarkdownBlock.Heading })
        assertTrue("the table is a table", blocks.any { it is MarkdownBlock.Table })
        assertTrue(
            "no literal markdown is left in the prose",
            blocks.filterIsInstance<MarkdownBlock.Paragraph>().none {
                it.text.contains("##") || it.text.contains("|---")
            },
        )
    }

    @Test
    fun `fenced code still parses alongside the new blocks`() {
        val blocks = splitReasoning("## Title\n\n```kotlin\nval x = 1\n```\n\n- done")

        assertTrue(blocks.any { it is MarkdownBlock.Heading })
        assertEquals("val x = 1", (blocks.first { it is MarkdownBlock.Code } as MarkdownBlock.Code).code)
        assertTrue(blocks.any { it is MarkdownBlock.Bullets })
    }

    @Test
    fun `empty input produces nothing`() {
        assertTrue(splitReasoning("").isEmpty())
        assertTrue(splitReasoning("   \n  ").isEmpty())
    }
}
