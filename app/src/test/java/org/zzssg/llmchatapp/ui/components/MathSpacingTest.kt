package org.zzssg.llmchatapp.ui.components

import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The flattened form, which is what a formula inside a sentence turns into.
 *
 * Spacing is most of the point: without it an expression comes out as
 * `x+(x+1.00)=1.10`, which is legible but reads as a run of characters rather
 * than as maths. The assertions name the exact space characters, so a change in
 * the widths shows up as a diff rather than as a shrug.
 */
class MathSpacingTest {

    /** Around a binary operator. */
    private val thin = " "

    /** Around a relation, which TeX sets wider. */
    private val medium = " "

    private fun render(latex: String): String =
        buildAnnotatedString { appendInlineMath(latex, 16.sp) }.text

    @Test
    fun `binary operators and relations take space`() {
        assertEquals("x${thin}+${thin}1.00", render("x + 1.00"))
        assertEquals("2x${medium}=${medium}0.10", render("2x = 0.10"))
    }

    /** A sign is part of its term; an operation separates two of them. */
    @Test
    fun `a leading minus is a sign and takes none`() {
        assertEquals("−b", render("-b"))
        assertEquals("a${thin}−${thin}b", render("a - b"))
    }

    @Test
    fun `an operator after an opening bracket is a sign too`() {
        assertEquals("(−b)", render("(-b)"))
    }

    @Test
    fun `a lone symbol is left alone`() {
        assertEquals("x", render("x"))
        assertEquals("=", render("="))
    }

    // -- linearisation ------------------------------------------------------

    /**
     * Inline maths has to stay on the sentence's baseline, so the constructs
     * that need two dimensions are written the way they would be by hand.
     */
    @Test
    fun `a fraction becomes a solidus`() {
        assertEquals("a/b", render("\\frac{a}{b}"))
    }

    @Test
    fun `a compound fraction gets brackets so it still reads correctly`() {
        assertEquals("(a${thin}+${thin}b)/2", render("\\frac{a+b}{2}"))
    }

    @Test
    fun `a root is written with its bracket`() {
        assertEquals("√2", render("\\sqrt{2}"))
        assertEquals("√(b${thin}−${thin}1)", render("\\sqrt{b - 1}"))
    }

    /** Scripts keep their characters; the baseline shift is what carries them. */
    @Test
    fun `scripts survive flattening`() {
        assertEquals("x2", render("x^2"))
        assertEquals("xi", render("x_i"))
    }
}
