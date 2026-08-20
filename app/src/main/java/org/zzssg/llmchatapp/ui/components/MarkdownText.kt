package org.zzssg.llmchatapp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.zzssg.llmchatapp.ui.theme.CodeTextStyle
import org.zzssg.llmchatapp.ui.theme.Spacing

/**
 * Minimal markdown rendering for model output.
 *
 * Deliberately not a full parser: models overwhelmingly emit fenced code blocks,
 * inline code, bold and italic, and those four cover almost everything a chat
 * reply contains. Anything else falls through as plain text, which is still a
 * large improvement over the previous UI, where a code block arrived as an
 * unformatted wall of characters inside a chat bubble.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    onCopyCode: (String) -> Unit = {},
) {
    val blocks = remember(text) { splitReasoning(text) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> SelectionContainer {
                    Text(
                        text = renderInline(block.text),
                        style = LocalTextStyle.current,
                    )
                }

                is MarkdownBlock.Code -> CodeBlock(block, onCopyCode)

                is MarkdownBlock.Reasoning -> ReasoningBlock(block)
            }
        }
    }
}

/**
 * Collapsed by default: the reasoning trace is context for the answer, not the
 * answer. It stays reachable for anyone who wants to see how the model got there.
 */
@Composable
private fun ReasoningBlock(block: MarkdownBlock.Reasoning) {
    var expanded by rememberSaveable(block.text) { mutableStateOf(false) }

    // Expandable as soon as the block is closed, even when the trace came back
    // empty -- a header that looks the same but does nothing when tapped reads
    // as a broken control. The expanded state says so explicitly instead.
    val canExpand = block.complete

    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron",
    )

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .then(
                    if (canExpand) {
                        Modifier.clickable(
                            onClickLabel = if (expanded) "Hide reasoning" else "Show reasoning",
                        ) { expanded = !expanded }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(Spacing.sm))
                Text(
                    text = if (block.complete) "Reasoning" else "Thinking",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                if (canExpand) {
                    // A chevron is the affordance: without it the header is just
                    // text and nothing suggests it can be opened.
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(rotation),
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.size(Spacing.sm))
                SelectionContainer {
                    Text(
                        text = block.text.ifBlank {
                            "This model reported no reasoning for this reply."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = if (block.text.isBlank()) FontStyle.Italic else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MarkdownBlock.Code, onCopy: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.md, top = Spacing.xs, end = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.language.ifEmpty { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = { onCopy(block.code) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy code",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = block.code,
                    style = CodeTextStyle,
                    // Code must scroll rather than wrap: rewrapped source is
                    // unreadable and misleading about indentation.
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(Spacing.md),
                )
            }
        }
    }
}

internal sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Code(val language: String, val code: String) : MarkdownBlock

    /**
     * A reasoning model's `<think>` block. [complete] is false while the closing
     * tag has not arrived yet, which is how the UI knows to say "Thinking" rather
     * than offering the finished trace.
     */
    data class Reasoning(val text: String, val complete: Boolean) : MarkdownBlock
}

const val THINK_OPEN = "<think>"
const val THINK_CLOSE = "</think>"

/**
 * Splits `<think>...</think>` out of [text] before anything else runs.
 *
 * Reasoning models put their scratchpad inline with the answer. Rendering it as
 * ordinary prose buries the actual reply -- on a short answer the trace can be
 * the entire visible message.
 */
internal fun splitReasoning(text: String): List<MarkdownBlock> {
    if (!text.contains(THINK_OPEN)) return parseProse(text)

    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0

    while (index < text.length) {
        val open = text.indexOf(THINK_OPEN, index)
        if (open < 0) {
            blocks += parseProse(text.substring(index).trim())
            break
        }

        // Whitespace adjacent to a think block is a separator, not content.
        // Without trimming, the answer arrives with the blank lines that
        // followed the closing tag still attached to it.
        blocks += parseProse(text.substring(index, open).trim())

        val bodyStart = open + THINK_OPEN.length
        val close = text.indexOf(THINK_CLOSE, bodyStart)
        if (close < 0) {
            // Still streaming: everything after the open tag is reasoning so far.
            blocks += MarkdownBlock.Reasoning(text.substring(bodyStart).trim(), complete = false)
            break
        }

        blocks += MarkdownBlock.Reasoning(text.substring(bodyStart, close).trim(), complete = true)
        index = close + THINK_CLOSE.length
    }

    return blocks
}

/**
 * Splits on ``` fences. An unterminated fence is treated as a code block that is
 * still streaming in, so a block does not flicker between plain and formatted
 * while tokens arrive.
 */
private fun parseProse(text: String): List<MarkdownBlock> {
    if (!text.contains("```")) {
        return if (text.isEmpty()) emptyList() else listOf(MarkdownBlock.Paragraph(text))
    }

    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.lines()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        if (line.trimStart().startsWith("```")) {
            val language = line.trimStart().removePrefix("```").trim()
            val code = StringBuilder()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[index])
                index++
            }
            index++ // consume the closing fence, if present
            blocks += MarkdownBlock.Code(language, code.toString())
        } else {
            val paragraph = StringBuilder()
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(lines[index])
                index++
            }
            val content = paragraph.toString().trim('\n')
            if (content.isNotBlank()) blocks += MarkdownBlock.Paragraph(content)
        }
    }

    return blocks
}

/** Applies `**bold**`, `*italic*` and `` `code` `` spans. */
private fun renderInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end < 0) { append(text.substring(i)); return@buildAnnotatedString }
                withSpan(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
            }

            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end < 0) { append(text.substring(i)); return@buildAnnotatedString }
                withSpan(SpanStyle(fontFamily = FontFamily.Monospace)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
            }

            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end < 0) { append(text.substring(i)); return@buildAnnotatedString }
                withSpan(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
            }

            else -> {
                // Copy through to the next character that could start a span.
                val next = text.indexOfAny(charArrayOf('*', '`'), i + 1)
                val stop = if (next < 0) text.length else next
                append(text.substring(i, stop))
                i = stop
            }
        }
    }
}

private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withSpan(
    style: SpanStyle,
    block: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit,
) {
    val start = length
    block()
    addStyle(style, start, length)
}
