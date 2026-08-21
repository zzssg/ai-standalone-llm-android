package org.zzssg.llmchatapp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathDelimitersTest {

    // -- the money problem --------------------------------------------------

    /**
     * The reason the dollar rules exist. A price opens a dollar and never closes
     * it, so a naive matcher does not just misrender a symbol -- it swallows the
     * rest of the sentence into a formula at the next price.
     */
    @Test
    fun `prices are left as text`() {
        val chunks = splitInlineMath("Earns \$50/hr at Job A and \$80/day at Job B.")

        assertEquals(
            listOf(InlineChunk.Text("Earns \$50/hr at Job A and \$80/day at Job B.")),
            chunks,
        )
    }

    /** The shape from the app's own render sample: prices inside a table row. */
    @Test
    fun `a table cell of prices stays text`() {
        val cell = "| Total income from multiple jobs | Earns \$50/hr at Job A |"

        assertEquals(listOf(InlineChunk.Text(cell)), splitInlineMath(cell))
    }

    @Test
    fun `a price and a real formula in one sentence both come out right`() {
        val chunks = splitInlineMath("The bat costs \$1.00 more, so the ball is \$x\$.")

        assertEquals(
            listOf(
                InlineChunk.Text("The bat costs \$1.00 more, so the ball is "),
                InlineChunk.Formula("x"),
                InlineChunk.Text("."),
            ),
            chunks,
        )
    }

    // -- inline maths -------------------------------------------------------

    @Test
    fun `a variable in dollars is a formula`() {
        assertEquals(
            listOf(InlineChunk.Text("Let "), InlineChunk.Formula("x"), InlineChunk.Text(" be the ball.")),
            splitInlineMath("Let \$x\$ be the ball."),
        )
    }

    /** A number is fine as maths when nothing about it reads like a price. */
    @Test
    fun `a bare number in dollars is a formula`() {
        assertEquals(listOf(InlineChunk.Formula("1.10")), splitInlineMath("\$1.10\$"))
    }

    /**
     * An equation that opens with a coefficient. The first attempt at the price
     * rule rejected anything starting with a digit that contained a space, which
     * threw this out along with the prices, and it showed up on screen as raw
     * dollars in the middle of a worked solution.
     */
    @Test
    fun `an equation starting with a coefficient is a formula`() {
        assertEquals(
            listOf(
                InlineChunk.Text("Solving gives "),
                InlineChunk.Formula("2x = 0.10"),
                InlineChunk.Text("."),
            ),
            splitInlineMath("Solving gives \$2x = 0.10\$."),
        )
    }

    @Test
    fun `the parenthesis form is recognised`() {
        assertEquals(
            listOf(InlineChunk.Text("where "), InlineChunk.Formula("x + 1")),
            splitInlineMath("where \\(x + 1\\)"),
        )
    }

    @Test
    fun `a formula may not span a line`() {
        val text = "cost \$x\nand y\$ here"

        assertEquals(listOf(InlineChunk.Text(text)), splitInlineMath(text))
    }

    @Test
    fun `text with no dollars is one chunk`() {
        assertEquals(listOf(InlineChunk.Text("Just prose.")), splitInlineMath("Just prose."))
    }

    /**
     * Shell examples are full of dollars, and a command the reader is meant to
     * type verbatim must not come back italicised.
     */
    @Test
    fun `dollars inside a code span are code`() {
        val text = "Run `echo \$PATH:\$HOME` first."

        assertEquals(listOf(InlineChunk.Text(text)), splitInlineMath(text))
    }

    @Test
    fun `a formula after a code span is still found`() {
        val chunks = splitInlineMath("Set `PATH` then let \$x = 1\$.")

        assertEquals(InlineChunk.Text("Set `PATH` then let "), chunks[0])
        assertEquals(InlineChunk.Formula("x = 1"), chunks[1])
    }

    @Test
    fun `an unclosed dollar is text`() {
        assertEquals(listOf(InlineChunk.Text("costs \$5 today")), splitInlineMath("costs \$5 today"))
    }

    // -- display maths ------------------------------------------------------

    @Test
    fun `display maths mid-sentence breaks the paragraph apart`() {
        val blocks = splitReasoning("Set up the equation: \$\$x + (x + 1.00) = 1.10\$\$ and solve.")

        assertEquals(MarkdownBlock.Paragraph("Set up the equation:"), blocks[0])
        assertEquals(MarkdownBlock.Math("x + (x + 1.00) = 1.10"), blocks[1])
        assertEquals(MarkdownBlock.Paragraph("and solve."), blocks[2])
    }

    @Test
    fun `the bracket form is display maths too`() {
        val blocks = splitReasoning("So:\n\\[ x = 0.05 \\]")

        assertEquals(MarkdownBlock.Paragraph("So:"), blocks[0])
        assertEquals(MarkdownBlock.Math("x = 0.05"), blocks[1])
    }

    @Test
    fun `display maths on its own lines survives the line breaks`() {
        val blocks = splitReasoning("\$\$\n\\begin{aligned}\na &= 1\n\\end{aligned}\n\$\$")

        val math = blocks.single() as MarkdownBlock.Math
        assertTrue(math.latex.contains("aligned"))
    }

    /**
     * Mid-generation the closing delimiter has not arrived. Showing the source
     * until it does would flash raw TeX into the transcript on every formula.
     */
    @Test
    fun `an unterminated formula forms as it streams`() {
        val blocks = splitReasoning("The total is \$\$x + (x + 1.00")

        assertEquals(MarkdownBlock.Paragraph("The total is"), blocks[0])
        assertEquals(MarkdownBlock.Math("x + (x + 1.00"), blocks[1])
    }

    @Test
    fun `a fence labelled as maths is a formula not a listing`() {
        val blocks = splitReasoning("```math\nE = mc^2\n```")

        assertEquals(listOf(MarkdownBlock.Math("E = mc^2")), blocks)
    }

    @Test
    fun `an ordinary code fence is still a listing`() {
        val blocks = splitReasoning("```kotlin\nval x = 1\n```")

        assertEquals(listOf(MarkdownBlock.Code("kotlin", "val x = 1")), blocks)
    }

    /** Dollars inside code are code, whatever they look like. */
    @Test
    fun `maths delimiters inside a fence are left alone`() {
        val blocks = splitReasoning("```sh\necho \$\$ and \$HOME\n```")

        assertEquals(listOf(MarkdownBlock.Code("sh", "echo \$\$ and \$HOME")), blocks)
    }
}
