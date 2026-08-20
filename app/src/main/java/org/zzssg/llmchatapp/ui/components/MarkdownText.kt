package org.zzssg.llmchatapp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.zzssg.llmchatapp.ui.theme.CodeTextStyle
import org.zzssg.llmchatapp.ui.theme.Spacing

/**
 * Renders a model reply.
 *
 * Models write structured prose -- headings, tables, lists, rules, fenced code --
 * and showing that as literal `##` and pipe characters makes a competent answer
 * look like a broken one. Parsing lives in MarkdownBlocks.kt so it can be tested
 * without a device; this file only draws.
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
                    Text(renderInline(block.text), style = LocalTextStyle.current)
                }

                is MarkdownBlock.Heading -> HeadingBlock(block)
                is MarkdownBlock.Code -> CodeBlock(block, onCopyCode)
                is MarkdownBlock.Table -> TableBlock(block)
                is MarkdownBlock.Bullets -> BulletsBlock(block)
                is MarkdownBlock.Reasoning -> ReasoningBlock(block)

                MarkdownBlock.Rule -> Spacer(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xs)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
    }
}

@Composable
private fun HeadingBlock(block: MarkdownBlock.Heading) {
    // Chat bubbles are narrow, so headings separate themselves by weight and a
    // modest step in size rather than the display-scale jumps a document uses.
    val style = when (block.level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }

    SelectionContainer {
        Text(
            text = renderInline(block.text),
            style = style.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

@Composable
private fun BulletsBlock(block: MarkdownBlock.Bullets) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        block.items.forEachIndexed { index, item ->
            Row {
                Text(
                    text = if (block.ordered) "${block.start + index}." else "•",
                    style = LocalTextStyle.current,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // A fixed gutter keeps text edges aligned however wide the
                    // marker is.
                    modifier = Modifier.widthIn(min = 22.dp),
                )
                SelectionContainer {
                    Text(renderInline(item), style = LocalTextStyle.current)
                }
            }
        }
    }
}

private val TABLE_COLUMN_WIDTH = 132.dp

@Composable
private fun TableBlock(block: MarkdownBlock.Table) {
    val border = MaterialTheme.colorScheme.outlineVariant
    val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Tables are usually wider than a phone. Scrolling the table alone keeps
        // its columns intact and leaves the transcript scrolling vertically.
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(Spacing.sm)
        ) {
            TableRow(block.header, border, isHeader = true)
            block.rows.forEach { row ->
                Spacer(
                    Modifier
                        // Cells plus the 1dp separators between them, otherwise
                        // the rule stops short of the last column.
                        .width(TABLE_COLUMN_WIDTH * columns + 1.dp * (columns - 1))
                        .height(1.dp)
                        .background(border)
                )
                TableRow(row, border, isHeader = false)
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, border: Color, isHeader: Boolean) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) {
                Spacer(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(border)
                )
            }
            SelectionContainer {
                Text(
                    text = renderInline(cell),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    modifier = Modifier
                        .width(TABLE_COLUMN_WIDTH)
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
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
                IconButton(onClick = { onCopy(block.code) }, modifier = Modifier.size(32.dp)) {
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
                    // Code scrolls rather than wraps: rewrapped source is
                    // unreadable and misleading about indentation.
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(Spacing.md),
                )
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
    val canExpand = block.complete
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

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

/** Applies `**bold**`, `*italic*` and `` `code` `` spans. */
internal fun renderInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end < 0) { append(text.substring(i)); return@buildAnnotatedString }
                withSpan(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                i = end + 2
            }

            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end < 0) { append(text.substring(i)); return@buildAnnotatedString }
                withSpan(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
            }

            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end < 0) { append(text.substring(i)); return@buildAnnotatedString }
                withSpan(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                i = end + 1
            }

            else -> {
                val next = text.indexOfAny(charArrayOf('*', '`'), i + 1)
                val stop = if (next < 0) text.length else next
                append(text.substring(i, stop))
                i = stop
            }
        }
    }
}

private inline fun AnnotatedString.Builder.withSpan(
    style: SpanStyle,
    block: AnnotatedString.Builder.() -> Unit,
) {
    val start = length
    block()
    addStyle(style, start, length)
}
