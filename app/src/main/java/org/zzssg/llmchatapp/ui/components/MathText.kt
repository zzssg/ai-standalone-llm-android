package org.zzssg.llmchatapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.zzssg.llmchatapp.ui.theme.Spacing

/**
 * Draws a formula.
 *
 * Two renderers, because a chat transcript needs two different things. Inside a
 * sentence a formula has to sit on the text's own baseline, so [appendMath]
 * flattens it into the surrounding AnnotatedString and linearises the few
 * constructs that cannot be flattened. Set on its own line it can afford real
 * two-dimensional layout, which is what [MathBlock] does: stacked fractions,
 * roots under a bar, scripts above and below.
 *
 * Parsing is in LatexMath.kt so it can be tested without a device.
 */

private const val SCRIPT_SCALE = 0.72f
private const val FRACTION_SCALE = 0.95f

/** Where the radical's tip sits, as a share of the font size below the box top. */
private const val RADICAL_BAR_OFFSET = 0.16f

// -- display ---------------------------------------------------------------

/** A formula on its own line, scrollable sideways when it does not fit. */
@Composable
fun MathBlock(latex: String, modifier: Modifier = Modifier) {
    val node = remember(latex) { parseMath(latex) }
    val style = LocalTextStyle.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.Center,
    ) {
        MathLines(node, style)
    }
}

/** Splits a row on its hard breaks and stacks whatever comes out. */
@Composable
private fun MathLines(node: MathNode, style: TextStyle) {
    val lines = remember(node) { splitLines(node) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        lines.forEach { line -> MathRow(line, style) }
    }
}

@Composable
private fun MathRow(items: List<MathNode>, style: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Consecutive flattenable nodes are drawn as one Text so the font can
        // kern and space them as it would any other run of characters. Only the
        // genuinely two-dimensional pieces become their own composable.
        val runs = remember(items, style.fontSize) { groupRuns(spaced(items), style.fontSize) }
        runs.forEach { run ->
            when (run) {
                is MathRun.Flat -> Text(run.text, style = style)
                is MathRun.Node -> MathNodeView(run.node, style)
            }
        }
    }
}

@Composable
private fun MathNodeView(node: MathNode, style: TextStyle) {
    when (node) {
        is MathNode.Frac -> FractionView(node, style)
        is MathNode.Sqrt -> RootView(node, style)
        is MathNode.Script -> ScriptView(node, style)
        is MathNode.Row -> MathRow(node.items, style)
        else -> Text(buildAnnotatedString { appendMath(node, style.fontSize) }, style = style)
    }
}

@Composable
private fun FractionView(node: MathNode.Frac, style: TextStyle) {
    val inner = style.copy(fontSize = style.fontSize * FRACTION_SCALE)

    Column(
        // Max intrinsic width, so the rule below spans the wider of the two
        // halves rather than stretching to the whole bubble.
        modifier = Modifier
            .width(IntrinsicSize.Max)
            .padding(horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MathNodeView(node.numerator, inner)
        Row(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LocalContentColor.current)
        ) {}
        MathNodeView(node.denominator, inner)
    }
}

@Composable
private fun RootView(node: MathNode.Sqrt, style: TextStyle) {
    // The radical glyph cannot stretch over what follows it, so the bar is drawn.
    // It has to meet the tip of the glyph, which sits near the cap height rather
    // than at the top of the text box -- a bar placed above the box floats clear
    // of the radical and reads as an unrelated line. Hence the offset, taken
    // from the font size so it tracks the surrounding text.
    val color = LocalContentColor.current
    val barY = with(LocalDensity.current) { style.fontSize.toPx() * RADICAL_BAR_OFFSET }
    val barWidth = with(LocalDensity.current) { 1.dp.toPx() }

    Row(verticalAlignment = Alignment.Bottom) {
        node.index?.let { index ->
            Text(
                buildAnnotatedString { appendMath(index, style.fontSize * SCRIPT_SCALE) },
                style = style.copy(fontSize = style.fontSize * SCRIPT_SCALE),
            )
        }
        Text("√", style = style)
        Box(
            Modifier
                .drawBehind {
                    drawLine(
                        color = color,
                        start = Offset(0f, barY),
                        end = Offset(size.width, barY),
                        strokeWidth = barWidth,
                    )
                }
                .padding(top = 2.dp, start = 1.dp)
        ) {
            MathNodeView(node.radicand, style)
        }
    }
}

/**
 * A base whose scripts cannot sit in an AnnotatedString.
 *
 * Big operators put their limits above and below in display maths, which is the
 * difference between reading a sum's range at a glance and picking it out of a
 * cluster of small type to the right of the sigma.
 */
@Composable
private fun ScriptView(node: MathNode.Script, style: TextStyle) {
    val small = style.tightened(SCRIPT_SCALE)

    if (node.base.isBigOperator()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 2.dp),
        ) {
            node.sup?.let { MathNodeView(it, small) }
            MathNodeView(node.base, style)
            node.sub?.let { MathNodeView(it, small) }
        }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        MathNodeView(node.base, style)
        Column(horizontalAlignment = Alignment.Start) {
            node.sup?.let { MathNodeView(it, small) }
            node.sub?.let { MathNodeView(it, small) }
        }
    }
}

/**
 * The same style at [scale], with the paragraph's leading taken off it.
 *
 * A limit set with normal line spacing carries the font's padding above and
 * below, which parks it a visible gap away from the operator it belongs to and
 * makes a stacked sum read as three separate lines. Trimming is the documented
 * way to get the box down to the glyphs; a smaller lineHeight on its own is
 * ignored once it falls under the font's own minimum.
 */
private fun TextStyle.tightened(scale: Float): TextStyle {
    val size = fontSize * scale
    return copy(
        fontSize = size,
        lineHeight = size,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
    )
}

/** Operators whose limits belong above and below, not beside. */
private const val BIG_OPERATORS = "∑∏∐⋃⋂"

private fun MathNode.isBigOperator(): Boolean = when (this) {
    is MathNode.Sym -> text.length == 1 && text.first() in BIG_OPERATORS
    is MathNode.Row -> items.singleOrNull()?.isBigOperator() == true
    else -> false
}

// -- inline ----------------------------------------------------------------

/**
 * Appends [latex] to a running AnnotatedString, on the text's own baseline.
 *
 * Anything that cannot be flattened is linearised instead: a fraction becomes
 * `a/b`, bracketed when either half is compound, and a root becomes `√(x)`. That
 * is how these are written by hand anyway, and it keeps a sentence on one line
 * instead of forcing a two-line stack into the middle of it.
 */
fun AnnotatedString.Builder.appendInlineMath(latex: String, fontSize: TextUnit) {
    appendMath(parseMath(latex), fontSize)
}

internal fun AnnotatedString.Builder.appendMath(node: MathNode, fontSize: TextUnit) {
    when (node) {
        is MathNode.Row -> spaced(node.items).forEach { appendMath(it, fontSize) }

        is MathNode.Sym ->
            if (node.italic) {
                withMathStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(node.text) }
            } else {
                append(node.text)
            }

        is MathNode.Space -> append(spaceFor(node.ems))

        MathNode.Break -> append('\n')

        is MathNode.Script -> {
            appendMath(node.base, fontSize)
            node.sup?.let {
                withMathStyle(
                    SpanStyle(
                        baselineShift = BaselineShift.Superscript,
                        fontSize = fontSize * SCRIPT_SCALE,
                    )
                ) { appendMath(it, fontSize * SCRIPT_SCALE) }
            }
            node.sub?.let {
                withMathStyle(
                    SpanStyle(
                        baselineShift = BaselineShift.Subscript,
                        fontSize = fontSize * SCRIPT_SCALE,
                    )
                ) { appendMath(it, fontSize * SCRIPT_SCALE) }
            }
        }

        is MathNode.Frac -> {
            appendBracketed(node.numerator, fontSize)
            append('/')
            appendBracketed(node.denominator, fontSize)
        }

        is MathNode.Sqrt -> {
            node.index?.let { appendMath(it, fontSize * SCRIPT_SCALE) }
            append('√')
            appendBracketed(node.radicand, fontSize)
        }
    }
}

/** Brackets a part only when leaving it bare would change how it reads. */
private fun AnnotatedString.Builder.appendBracketed(node: MathNode, fontSize: TextUnit) {
    val compound = node is MathNode.Row && node.items.size > 1
    if (compound) append('(')
    appendMath(node, fontSize)
    if (compound) append(')')
}

private fun spaceFor(ems: Float): String = when {
    ems <= 0f -> ""
    ems < 0.25f -> " " // thin space
    ems < 0.5f -> " " // four-per-em
    ems < 1.5f -> " " // em
    else -> "  "
}

private inline fun AnnotatedString.Builder.withMathStyle(
    style: SpanStyle,
    block: AnnotatedString.Builder.() -> Unit,
) {
    val start = length
    block()
    addStyle(style, start, length)
}

// -- shared helpers --------------------------------------------------------

private sealed interface MathRun {
    data class Flat(val text: AnnotatedString) : MathRun
    data class Node(val node: MathNode) : MathRun
}

/** Set with space on both sides, at the widths TeX uses. */
private const val RELATIONS = "=≠≤≥<>≈≡∼≃≅∝≪≫∈∉∋⊂⊆⊃⊇→←↔⇒⇐⇔↦"
private const val BINARY_OPERATORS = "+−±∓×⋅÷∗⋆∘∙∪∩∖⊕⊗∧∨"

/** Nothing on the left of an operator means it is a sign, not an operation. */
private const val OPENERS = "([{⟨|"

/**
 * Inserts the space TeX would put around operators and relations.
 *
 * Without it a formula comes out as `x+(x+1.00)=1.10`, which is legible but
 * reads as a string of characters rather than as an expression. The distinction
 * that matters is unary from binary: the minus in `-b` is part of the term and
 * takes no space, while the one in `a - b` separates two terms and takes it.
 */
private fun spaced(items: List<MathNode>): List<MathNode> {
    if (items.size < 2) return items

    val out = mutableListOf<MathNode>()
    items.forEachIndexed { index, item ->
        val symbol = (item as? MathNode.Sym)?.text?.takeIf { it.length == 1 }?.first()
        val ems = when (symbol) {
            null -> 0f
            in RELATIONS.toSet() -> 0.28f
            in BINARY_OPERATORS.toSet() -> 0.17f
            else -> 0f
        }

        if (ems > 0f) {
            val previous = items.getOrNull(index - 1)
            // A sign rather than an operation: at the start of the row, or right
            // after another operator or an opening bracket.
            val unary = index == 0 || previous.isOperatorLike()
            val trailing = index < items.size - 1
            if (!unary) out += MathNode.Space(ems)
            out += item
            if (!unary && trailing) out += MathNode.Space(ems)
            return@forEachIndexed
        }

        out += item
    }
    return out
}

private fun MathNode?.isOperatorLike(): Boolean {
    val text = (this as? MathNode.Sym)?.text ?: return false
    if (text.length != 1) return false
    val c = text.first()
    return c in RELATIONS || c in BINARY_OPERATORS || c in OPENERS
}

/** Splits a node into lines at its hard breaks. */
private fun splitLines(node: MathNode): List<List<MathNode>> {
    val items = if (node is MathNode.Row) node.items else listOf(node)
    val lines = mutableListOf<List<MathNode>>()
    var current = mutableListOf<MathNode>()

    items.forEach { item ->
        if (item is MathNode.Break) {
            lines += current
            current = mutableListOf()
        } else {
            current += item
        }
    }
    lines += current
    return lines.filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList()) }
}

/** Batches what can share a Text, and isolates what cannot. */
private fun groupRuns(items: List<MathNode>, fontSize: TextUnit): List<MathRun> {
    val runs = mutableListOf<MathRun>()
    var pending = mutableListOf<MathNode>()

    fun flush() {
        if (pending.isEmpty()) return
        val batch = pending
        pending = mutableListOf()
        runs += MathRun.Flat(buildAnnotatedString { batch.forEach { appendMath(it, fontSize) } })
    }

    items.forEach { item ->
        if (needsLayout(item)) {
            flush()
            runs += MathRun.Node(item)
        } else {
            pending += item
        }
    }
    flush()
    return runs
}

/** True for the constructs that only read correctly in two dimensions. */
private fun needsLayout(node: MathNode): Boolean = when (node) {
    is MathNode.Frac, is MathNode.Sqrt -> true
    is MathNode.Row -> node.items.any { needsLayout(it) }
    is MathNode.Script ->
        // A big operator's limits stack, which no baseline shift can express.
        node.base.isBigOperator() ||
            needsLayout(node.base) ||
            node.sup?.let { needsLayout(it) } == true ||
            node.sub?.let { needsLayout(it) } == true
    else -> false
}

/** Kept for callers that want a size without a theme in scope. */
internal val DefaultMathFontSize: TextUnit = 16.sp
