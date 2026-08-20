package org.zzssg.llmchatapp.ui.components

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

    /**
     * A reasoning model's scratchpad. [complete] is false while the closing tag
     * has not arrived, which is how the UI knows to say "Thinking" rather than
     * offering a finished trace.
     */
    data class Reasoning(val text: String, val complete: Boolean) : MarkdownBlock
}

const val THINK_OPEN = "<think>"
const val THINK_CLOSE = "</think>"

/**
 * Splits reasoning out of [text] before anything else is parsed.
 *
 * Handles the case that made the block look broken on screen: when thinking is
 * switched on, the *opening* tag is part of the prompt, not of the reply, so
 * generation starts already inside the block and the only tag that ever arrives
 * is the closing one. A parser that waits for `<think>` therefore renders the
 * whole scratchpad as the answer and leaves a stray `</think>` in the middle of
 * it. A closing tag with nothing opened means everything before it is reasoning.
 */
fun splitReasoning(text: String): List<MarkdownBlock> {
    if (!text.contains(THINK_OPEN) && !text.contains(THINK_CLOSE)) return parseProse(text)

    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0

    while (index < text.length) {
        val open = text.indexOf(THINK_OPEN, index)
        val close = text.indexOf(THINK_CLOSE, index)

        // A closing tag reached before any opening one: the reply began inside
        // the block because the template pre-filled the opener.
        if (close >= 0 && (open < 0 || close < open)) {
            blocks += MarkdownBlock.Reasoning(text.substring(index, close).trim(), complete = true)
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

        blocks += MarkdownBlock.Reasoning(text.substring(bodyStart, bodyEnd).trim(), complete = true)
        index = bodyEnd + THINK_CLOSE.length
    }

    return blocks
}

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
                blocks += MarkdownBlock.Code(language, code.toString())
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
                val content = paragraph.toString().trim()
                if (content.isNotEmpty()) blocks += MarkdownBlock.Paragraph(content)
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
