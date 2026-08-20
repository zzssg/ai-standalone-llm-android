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

    // -- reasoning while it streams -----------------------------------------

    /**
     * The UX complaint: with thinking on, the scratchpad was shown as an ordinary
     * answer for the whole minute it took to write, and only became a reasoning
     * block once the closing tag arrived. The prompt is what knows, so the caller
     * passes the fact in.
     */
    @Test
    fun `a reply known to start inside a block is reasoning from the first token`() {
        val reasoning = splitReasoning("The user is asking", startsInReasoning = true)
            .single() as MarkdownBlock.Reasoning

        assertEquals("The user is asking", reasoning.text)
        assertTrue("unfinished, so it must not offer a finished trace", !reasoning.complete)
    }

    @Test
    fun `the block closes and the answer begins when the closing tag arrives`() {
        val blocks = splitReasoning(
            "Weighing the options.\n</think>\n\n## Answer\n\nBlue.",
            startsInReasoning = true,
        )

        assertEquals(MarkdownBlock.Reasoning("Weighing the options.", complete = true), blocks[0])
        assertEquals(MarkdownBlock.Heading(2, "Answer"), blocks[1])
        assertEquals(MarkdownBlock.Paragraph("Blue."), blocks[2])
    }

    /** Nothing has arrived yet: the block exists, empty, so the label can say so. */
    @Test
    fun `an empty reply that starts inside a block is an empty reasoning block`() {
        val reasoning = splitReasoning("", startsInReasoning = true)
            .single() as MarkdownBlock.Reasoning

        assertEquals("", reasoning.text)
        assertTrue(!reasoning.complete)
    }

    /** Thinking off: the prompt closed the block, so none of the reply is reasoning. */
    @Test
    fun `without the flag a tagless reply is all answer`() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph("Blue.")),
            splitReasoning("Blue.", startsInReasoning = false),
        )
    }

    /**
     * With thinking on, a model that sees nothing to deliberate closes the block
     * at once. An empty box announcing that on most replies is clutter.
     */
    @Test
    fun `a block the model closed without using is dropped`() {
        val blocks = splitReasoning("\n</think>\n\nBlue.", startsInReasoning = true)

        assertEquals(listOf(MarkdownBlock.Paragraph("Blue.")), blocks)
    }

    @Test
    fun `an empty block written out in full is dropped too`() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph("Blue.")),
            splitReasoning("<think></think>\n\nBlue."),
        )
    }

    // -- answerOnly ---------------------------------------------------------

    @Test
    fun `answerOnly drops a block whose opener was in the prompt`() {
        val text = "Deliberating at length.\n</think>\n\nThe answer is blue."

        assertEquals("The answer is blue.", answerOnly(text, startsInReasoning = true))
        // The same without the flag: a closing tag with no opener is enough on
        // its own, which is what keeps replies stored earlier copying correctly.
        assertEquals("The answer is blue.", answerOnly(text))
    }

    @Test
    fun `answerOnly drops an ordinary closed block`() {
        assertEquals("Blue.", answerOnly("<think>weighing</think>\n\nBlue."))
    }

    /** Copying mid-thought should paste nothing rather than the scratchpad. */
    @Test
    fun `answerOnly is empty while only reasoning has been written`() {
        assertEquals("", answerOnly("still thinking about it", startsInReasoning = true))
        assertEquals("", answerOnly("<think>still thinking about it"))
    }

    @Test
    fun `answerOnly leaves a plain reply alone`() {
        assertEquals("Just an answer.", answerOnly("Just an answer."))
    }

    /** Copy has to paste the markdown the reader saw, structure and all. */
    @Test
    fun `answerOnly keeps markdown structure intact`() {
        val answer = "## Title\n\n| A | B |\n|---|---|\n| 1 | 2 |"

        assertEquals(answer, answerOnly("reasoning\n</think>\n\n" + answer, true))
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
