package org.zzssg.llmchatapp.ui.components

import org.zzssg.llmchatapp.llm.THINK_CLOSE
import org.zzssg.llmchatapp.llm.THINK_OPEN

/**
 * The structures a chat reply is made of.
 *
 * Deliberately a small set. Models emit headings, lists, tables, rules, fenced
 * code and reasoning traces constantly; everything else is rare enough that
 * falling through to plain text costs the reader nothing.
 */
sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Code(val language: String, val code: String) : MarkdownBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data class Bullets(val items: List<String>, val ordered: Boolean, val start: Int = 1) : MarkdownBlock
    data object Rule : MarkdownBlock

    /** Display maths, set on its own line. [latex] is the source between the delimiters. */
    data class Math(val latex: String) : MarkdownBlock

    /**
     * A reasoning model's scratchpad. [complete] is false while the closing tag
     * has not arrived, which is how the UI knows to say "Thinking" rather than
     * offering a finished trace.
     */
    data class Reasoning(val text: String, val complete: Boolean) : MarkdownBlock
}

/**
 * Splits reasoning out of [text] before anything else is parsed.
 *
 * [startsInReasoning] is the caller saying "the prompt left a block open, so
 * this reply begins inside it" -- see promptOpensReasoning. It is what makes the
 * block form as the reply streams: without it the scratchpad is indistinguishable
 * from an answer until the closing tag finally arrives, so the reader watches
 * paragraphs of deliberation appear as if they were the reply, then jump into a
 * collapsed box at the end.
 *
 * When the flag is not set, a closing tag with nothing opened still means
 * everything before it was reasoning. That keeps replies stored before the flag
 * existed rendering the way they always did.
 */
fun splitReasoning(text: String, startsInReasoning: Boolean = false): List<MarkdownBlock> {
    if (startsInReasoning) {
        val close = text.indexOf(THINK_CLOSE)
        // No closing tag yet: everything written so far is still scratchpad.
        if (close < 0) return listOf(MarkdownBlock.Reasoning(text.trim(), complete = false))

        return reasoning(text.substring(0, close)) +
            splitReasoning(text.substring(close + THINK_CLOSE.length))
    }

    if (!text.contains(THINK_OPEN) && !text.contains(THINK_CLOSE)) return parseProse(text)

    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0

    while (index < text.length) {
        val open = text.indexOf(THINK_OPEN, index)
        val close = text.indexOf(THINK_CLOSE, index)

        // A closing tag reached before any opening one: the reply began inside
        // the block because the template pre-filled the opener.
        if (close >= 0 && (open < 0 || close < open)) {
            blocks += reasoning(text.substring(index, close))
            index = close + THINK_CLOSE.length
            continue
        }

        if (open < 0) {
            blocks += parseProse(text.substring(index).trim())
            break
        }

        blocks += parseProse(text.substring(index, open).trim())

        val bodyStart = open + THINK_OPEN.length
        val bodyEnd = text.indexOf(THINK_CLOSE, bodyStart)
        if (bodyEnd < 0) {
            // Still streaming: everything after the opener is reasoning so far.
            blocks += MarkdownBlock.Reasoning(text.substring(bodyStart).trim(), complete = false)
            break
        }

        blocks += reasoning(text.substring(bodyStart, bodyEnd))
        index = bodyEnd + THINK_CLOSE.length
    }

    return blocks
}

/**
 * A finished reasoning block, or nothing at all when the model did not use it.
 *
 * With thinking on, a model that sees no need to deliberate closes the block
 * immediately. Drawing an empty box that says so on every such reply is noise;
 * the absence of a block already says the model answered directly.
 */
private fun reasoning(body: String): List<MarkdownBlock> {
    val text = body.trim()
    return if (text.isEmpty()) emptyList() else listOf(MarkdownBlock.Reasoning(text, complete = true))
}

private val MATH_FENCES = setOf("math", "latex", "tex")

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val RULE = Regex("""^\s{0,3}([-*_])\s*(\1\s*){2,}$""")
private val BULLET = Regex("""^\s{0,3}[-*+]\s+(.*)$""")
private val ORDERED = Regex("""^\s{0,3}(\d{1,9})[.)]\s+(.*)$""")
private val TABLE_DIVIDER = Regex("""^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$""")

/** Everything that is not a reasoning trace. */
internal fun parseProse(text: String): List<MarkdownBlock> {
    if (text.isBlank()) return emptyList()

    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        when {
            line.isBlank() -> i++

            line.trimStart().startsWith("```") -> {
                val language = line.trimStart().removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                i++ // consume the closing fence, if it arrived
                // A fence labelled as maths is a formula, not a listing.
                blocks += if (language.lowercase() in MATH_FENCES) {
                    MarkdownBlock.Math(code.toString().trim())
                } else {
                    MarkdownBlock.Code(language, code.toString())
                }
            }

            // Checked before the heading and bullet rules: "---" would otherwise
            // be read as a bullet, and "***" as emphasis.
            RULE.matches(line) -> {
                blocks += MarkdownBlock.Rule
                i++
            }

            HEADING.matches(line) -> {
                val (hashes, content) = HEADING.find(line)!!.destructured
                blocks += MarkdownBlock.Heading(hashes.length, content.trim())
                i++
            }

            isTableStart(lines, i) -> {
                val header = splitRow(lines[i])
                i += 2 // header and its divider
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += splitRow(lines[i])
                    i++
                }
                blocks += MarkdownBlock.Table(header, rows)
            }

            BULLET.matches(line) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && BULLET.matches(lines[i])) {
                    items += BULLET.find(lines[i])!!.groupValues[1].trim()
                    i++
                }
                blocks += MarkdownBlock.Bullets(items, ordered = false)
            }

            ORDERED.matches(line) -> {
                val start = ORDERED.find(line)!!.groupValues[1].toIntOrNull() ?: 1
                val items = mutableListOf<String>()
                while (i < lines.size && ORDERED.matches(lines[i])) {
                    items += ORDERED.find(lines[i])!!.groupValues[2].trim()
                    i++
                }
                blocks += MarkdownBlock.Bullets(items, ordered = true, start = start)
            }

            else -> {
                val paragraph = StringBuilder()
                while (i < lines.size && isPlainLine(lines, i)) {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(lines[i])
                    i++
                }
                // Display maths is pulled out here rather than in the line loop
                // above: models write it mid-sentence as often as on a line of
                // its own, so it has to break a paragraph apart.
                blocks += splitDisplayMath(paragraph.toString())
            }
        }
    }

    return blocks
}

private fun isPlainLine(lines: List<String>, i: Int): Boolean {
    val line = lines[i]
    return line.isNotBlank() &&
        !line.trimStart().startsWith("```") &&
        !RULE.matches(line) &&
        !HEADING.matches(line) &&
        !BULLET.matches(line) &&
        !ORDERED.matches(line) &&
        !isTableStart(lines, i)
}

/**
 * A table is only a table once its divider row confirms it.
 *
 * Without that check any sentence containing a pipe would start one, and models
 * write pipes in prose often enough for that to matter.
 */
private fun isTableStart(lines: List<String>, i: Int): Boolean =
    lines[i].contains('|') &&
        i + 1 < lines.size &&
        TABLE_DIVIDER.matches(lines[i + 1]) &&
        lines[i + 1].contains('-')

private fun splitRow(line: String): List<String> =
    line.trim()
        .removePrefix("|")
        .removeSuffix("|")
        .split('|')
        .map { it.trim() }

/**
 * [text] with its reasoning removed, for copying and for replaying history.
 *
 * A scratchpad is shown because it is interesting, not because it is part of the
 * answer. Copying a reply should paste what the model said, and sending a turn
 * back to the model should carry only its conclusion -- reasoning models are
 * trained to see their own answers in the history, and their templates strip
 * prior thinking, so returning it wastes context and degrades the next reply.
 */
fun answerOnly(text: String, startsInReasoning: Boolean = false): String {
    val firstOpen = text.indexOf(THINK_OPEN)
    val firstClose = text.indexOf(THINK_CLOSE)
    val beginsInside = startsInReasoning || (firstClose >= 0 && (firstOpen < 0 || firstClose < firstOpen))

    val body = when {
        !beginsInside -> text
        // Still mid-thought: there is no answer to take yet.
        firstClose < 0 -> ""
        else -> text.substring(firstClose + THINK_CLOSE.length)
    }

    val out = StringBuilder()
    var index = 0
    while (index < body.length) {
        val open = body.indexOf(THINK_OPEN, index)
        if (open < 0) {
            out.append(body, index, body.length)
            break
        }
        out.append(body, index, open)
        val close = body.indexOf(THINK_CLOSE, open + THINK_OPEN.length)
        // An unterminated block means the whole tail is reasoning.
        if (close < 0) break
        index = close + THINK_CLOSE.length
    }
    return out.toString().trim()
}
