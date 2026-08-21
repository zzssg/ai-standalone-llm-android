package org.zzssg.llmchatapp.ui.components

/**
 * Finding the formulas in a reply.
 *
 * Models mark maths four ways -- `$…$`, `$$…$$`, `\(…\)` and `\[…\]` -- and the
 * first of those collides with money, which the same models write constantly.
 * Everything fussy about that collision is decided here, away from both the
 * markdown parser and the renderer, so it can be tested on its own.
 */

/** A run of a line: either ordinary markdown, or a formula. */
sealed interface InlineChunk {
    data class Text(val text: String) : InlineChunk
    data class Formula(val latex: String) : InlineChunk
}

private const val MAX_INLINE_MATH = 200

/**
 * Splits [text] into literal runs and inline formulas.
 *
 * The dollar rules exist entirely to keep prices out of the maths renderer. A
 * price is written `$50/hr`, opened but never closed, so the damage from a naive
 * matcher is not a stray symbol -- it is the whole rest of the sentence being
 * swallowed into a formula at the next `$`. Three tests reject that:
 *
 *  - the content may not begin or end with a space, which rules out the common
 *    "earns `$`50/hr and `$`80/day" shape;
 *  - a run containing a space must also contain a operator or a command, so
 *    `2x = 0.10` is maths while `50/hr at Job A and ` is a price and some prose;
 *  - the run may not cross a line, and may not run on unreasonably long.
 *
 * A `$` that fails them is emitted as the character it is, and scanning resumes
 * after it, so the *next* dollar still gets its chance to open a formula.
 */
fun splitInlineMath(text: String): List<InlineChunk> {
    if (text.isEmpty()) return listOf(InlineChunk.Text(text))
    if (!text.contains('$') && !text.contains("\\(")) return listOf(InlineChunk.Text(text))

    val chunks = mutableListOf<InlineChunk>()
    val literal = StringBuilder()
    var i = 0

    fun flushLiteral() {
        if (literal.isNotEmpty()) {
            chunks += InlineChunk.Text(literal.toString())
            literal.clear()
        }
    }

    while (i < text.length) {
        val c = text[i]

        // A code span is copied through untouched. Shell examples are full of
        // dollars -- `$PATH:$HOME` would otherwise be read as a formula and
        // typeset in italics in the middle of a command the reader is meant to
        // run verbatim.
        if (c == '`') {
            val end = text.indexOf('`', i + 1)
            if (end >= 0) {
                literal.append(text, i, end + 1)
                i = end + 1
                continue
            }
        }

        // \( ... \)
        if (c == '\\' && i + 1 < text.length && text[i + 1] == '(') {
            val end = text.indexOf("\\)", i + 2)
            if (end >= 0) {
                flushLiteral()
                chunks += InlineChunk.Formula(text.substring(i + 2, end).trim())
                i = end + 2
                continue
            }
        }

        if (c == '$') {
            // A doubled dollar inside a paragraph is display maths, pulled out
            // by the block parser before this ever runs. Left alone here.
            val doubled = i + 1 < text.length && text[i + 1] == '$'
            val body = if (doubled) null else inlineMathAt(text, i)
            if (body != null) {
                flushLiteral()
                chunks += InlineChunk.Formula(body)
                i += body.length + 2
                continue
            }
        }

        literal.append(c)
        i++
    }

    flushLiteral()
    return chunks
}

/** The content of an inline formula opening at [start], or null if it is not one. */
private fun inlineMathAt(text: String, start: Int): String? {
    val end = text.indexOf('$', start + 1)
    if (end < 0) return null

    val body = text.substring(start + 1, end)
    if (body.isEmpty() || body.length > MAX_INLINE_MATH) return null
    if (body.contains('\n')) return null
    if (body.first().isWhitespace() || body.last().isWhitespace()) return null
    // Several words with nothing mathematical in them: a price and the sentence
    // that follows it, not a formula. A single token needs no such evidence --
    // a bare `x` or `1.10` has nowhere to hide a false positive.
    if (body.contains(' ') && MATH_EVIDENCE.none { it in body }) return null

    return body
}

/**
 * What tells a formula from prose that happens to sit between two prices.
 *
 * Deliberately excludes the solidus: `50/hr` is a rate, not a division, and it
 * is the single most common thing to appear right after a dollar sign.
 */
private const val MATH_EVIDENCE = "=+^_<>\\"

/**
 * Splits a paragraph into text and display formulas.
 *
 * Display maths is not always on a line of its own -- models routinely write
 * "the equation is $$x + 1 = 2$$" mid-sentence -- so this returns the paragraph
 * broken around whatever it finds, with blank fragments dropped.
 */
internal fun splitDisplayMath(text: String): List<MarkdownBlock> {
    if (!text.contains("$$") && !text.contains("\\[")) return prose(text)

    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0
    var literalStart = 0

    fun flushLiteral(upTo: Int) {
        if (upTo > literalStart) blocks += prose(text.substring(literalStart, upTo))
    }

    while (i < text.length) {
        val openedWith = when {
            text.startsWith("$$", i) -> "$$"
            text.startsWith("\\[", i) -> "\\["
            else -> null
        }
        if (openedWith == null) {
            i++
            continue
        }

        val closer = if (openedWith == "$$") "$$" else "\\]"
        val end = text.indexOf(closer, i + openedWith.length)
        if (end < 0) {
            // Unterminated: still streaming, most likely. Everything from the
            // opener on is the formula so far, so it forms as it arrives rather
            // than sitting as raw source until the closing delimiter lands.
            flushLiteral(i)
            blocks += MarkdownBlock.Math(text.substring(i + openedWith.length).trim())
            return blocks
        }

        flushLiteral(i)
        blocks += MarkdownBlock.Math(text.substring(i + openedWith.length, end).trim())
        i = end + closer.length
        literalStart = i
    }

    flushLiteral(text.length)
    return blocks
}

/** A paragraph fragment, dropped when it is only whitespace. */
private fun prose(text: String): List<MarkdownBlock> {
    val trimmed = text.trim()
    return if (trimmed.isEmpty()) emptyList() else listOf(MarkdownBlock.Paragraph(trimmed))
}
