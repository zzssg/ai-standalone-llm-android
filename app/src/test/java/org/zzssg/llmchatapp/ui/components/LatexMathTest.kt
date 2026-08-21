package org.zzssg.llmchatapp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexMathTest {

    // -- symbols and variables ----------------------------------------------

    @Test
    fun `a lone letter is a variable and is set in italic`() {
        val row = parseMath("x") as MathNode.Row

        assertEquals(listOf(MathNode.Sym("x", italic = true)), row.items)
    }

    @Test
    fun `digits stay upright and keep their decimal point`() {
        val row = parseMath("1.10") as MathNode.Row

        assertEquals(listOf(MathNode.Sym("1.10", italic = false)), row.items)
    }

    @Test
    fun `commands become the characters they stand for`() {
        assertEquals(symbols("α", "≤", "π", "∞"), parseMath("\\alpha \\leq \\pi \\infty").flat())
    }

    /** A hyphen is not a minus sign, and the difference is visible at this size. */
    @Test
    fun `a minus is typeset as a minus`() {
        assertEquals(listOf("x", "−", "1"), parseMath("x - 1").texts())
    }

    @Test
    fun `function names stay upright so they do not read as a product`() {
        val row = parseMath("\\sin x") as MathNode.Row

        assertEquals(MathNode.Sym("sin", italic = false), row.items[0])
        assertEquals(MathNode.Sym("x", italic = true), row.items[1])
    }

    // -- structure ----------------------------------------------------------

    @Test
    fun `a fraction takes both of its groups`() {
        val frac = parseMath("\\frac{a}{b}").single() as MathNode.Frac

        assertEquals(listOf("a"), frac.numerator.texts())
        assertEquals(listOf("b"), frac.denominator.texts())
    }

    /** TeX allows bare arguments, and models write them. */
    @Test
    fun `a fraction accepts unbraced arguments`() {
        val frac = parseMath("\\frac ab").single() as MathNode.Frac

        assertEquals(listOf("a"), frac.numerator.texts())
        assertEquals(listOf("b"), frac.denominator.texts())
    }

    @Test
    fun `a bare superscript takes one character and a braced one takes the group`() {
        val single = parseMath("x^2").single() as MathNode.Script
        val braced = parseMath("x^{10}").single() as MathNode.Script

        assertEquals(listOf("2"), single.sup!!.texts())
        assertEquals(listOf("10"), braced.sup!!.texts())
    }

    @Test
    fun `a base can carry both scripts in either order`() {
        val a = parseMath("x_i^2").single() as MathNode.Script
        val b = parseMath("x^2_i").single() as MathNode.Script

        assertEquals(listOf("i"), a.sub!!.texts())
        assertEquals(listOf("2"), a.sup!!.texts())
        assertEquals(a.sub!!.texts(), b.sub!!.texts())
        assertEquals(a.sup!!.texts(), b.sup!!.texts())
    }

    @Test
    fun `a root keeps its optional index`() {
        val plain = parseMath("\\sqrt{2}").single() as MathNode.Sqrt
        val cube = parseMath("\\sqrt[3]{x}").single() as MathNode.Sqrt

        assertEquals(null, plain.index)
        assertEquals(listOf("2"), plain.radicand.texts())
        assertEquals(listOf("3"), cube.index!!.texts())
    }

    @Test
    fun `text is taken verbatim rather than a character at a time`() {
        val row = parseMath("\\text{total cost}") as MathNode.Row

        assertEquals(listOf(MathNode.Sym("total cost", italic = false)), row.items)
    }

    // -- degradation --------------------------------------------------------

    /**
     * The parser must never reject: a formula shown a little plainly beats an
     * error where the answer should be. These are the shapes that would throw
     * in a stricter implementation.
     */
    @Test
    fun `unmatched braces do not swallow the rest of the formula`() {
        assertEquals(listOf("x", "+", "1"), parseMath("x + 1}").texts())
        assertEquals(listOf("a", "b"), parseMath("{a{b").texts())
    }

    @Test
    fun `a trailing backslash is harmless`() {
        assertEquals(listOf("x"), parseMath("x \\").texts())
    }

    @Test
    fun `an empty formula parses to nothing`() {
        assertEquals(emptyList<String>(), parseMath("").texts())
    }

    /** An unknown wrapper keeps what it wraps, which is the part that reads. */
    @Test
    fun `an unknown command yields its argument`() {
        assertEquals(listOf("A", "B"), parseMath("\\overline{AB}").texts())
        assertEquals(listOf("v"), parseMath("\\vec{v}").texts())
    }

    @Test
    fun `an unknown command with no argument is dropped`() {
        assertEquals(listOf("x"), parseMath("\\displaystyle x").texts())
    }

    /** The columns are gone, but the rows still mean something. */
    @Test
    fun `an environment keeps its rows and drops its wrapper`() {
        val node = parseMath("\\begin{aligned} a &= 1 \\\\ b &= 2 \\end{aligned}")
        val lines = node.texts().filter { it.isNotBlank() }

        assertTrue("both rows survive", lines.containsAll(listOf("a", "1", "b", "2")))
        assertTrue("the environment name is gone", lines.none { it.contains("aligned") })
    }

    @Test
    fun `sizing commands leave only their delimiter`() {
        assertEquals(listOf("(", "x", ")"), parseMath("\\left( x \\right)").texts())
        assertEquals(listOf("x"), parseMath("\\left. x \\right.").texts())
    }

    // -- helpers ------------------------------------------------------------

    private fun symbols(vararg text: String) = text.toList()

    private fun MathNode.single(): MathNode = (this as MathNode.Row).items.single()

    /** Every Sym in the tree, in order, for asserting on content not shape. */
    private fun MathNode.texts(): List<String> = when (this) {
        is MathNode.Row -> items.flatMap { it.texts() }
        is MathNode.Sym -> listOf(text)
        is MathNode.Frac -> numerator.texts() + denominator.texts()
        is MathNode.Sqrt -> (index?.texts() ?: emptyList()) + radicand.texts()
        is MathNode.Script ->
            base.texts() + (sub?.texts() ?: emptyList()) + (sup?.texts() ?: emptyList())
        MathNode.Break -> emptyList()
        is MathNode.Space -> emptyList()
    }

    private fun MathNode.flat(): List<String> = texts()
}
