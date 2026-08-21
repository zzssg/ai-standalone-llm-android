package org.zzssg.llmchatapp.ui.components

/**
 * The shapes a formula is built from.
 *
 * Deliberately a small set, matched to what a chat model actually writes. Real
 * TeX is a typesetting language; this is the handful of constructs that carry
 * meaning in an answer -- fractions, scripts, roots, and a large symbol table --
 * and everything outside it degrades to its readable parts rather than to an
 * error.
 */
sealed interface MathNode {
    data class Row(val items: List<MathNode>) : MathNode

    /**
     * One rendered token. [italic] separates variables from everything else: in
     * maths a lone Latin letter is a variable and is set in italic, while digits,
     * operators and function names stay upright. Getting that wrong is what makes
     * a hand-rolled formula look wrong without looking broken.
     */
    data class Sym(val text: String, val italic: Boolean = false) : MathNode

    data class Frac(val numerator: MathNode, val denominator: MathNode) : MathNode
    data class Sqrt(val radicand: MathNode, val index: MathNode? = null) : MathNode

    /** A base carrying a subscript, a superscript, or both. */
    data class Script(
        val base: MathNode,
        val sub: MathNode? = null,
        val sup: MathNode? = null,
    ) : MathNode

    /** A hard line break inside display maths, from a row separator. */
    data object Break : MathNode

    /** Horizontal space that survives into the output, from \quad and friends. */
    data class Space(val ems: Float) : MathNode
}

/**
 * Parses [latex] into a [MathNode] tree.
 *
 * Never throws and never rejects: malformed or unsupported input degrades to the
 * text it contains. A formula shown a little plainly is a far better outcome in a
 * chat transcript than an error where the answer should be.
 */
fun parseMath(latex: String): MathNode = MathParser(latex).parseRow(closer = null)

private class MathParser(private val src: String) {
    private var i = 0

    /**
     * Parses until [closer] (or the end), gathering atoms and attaching any
     * scripts that follow them.
     */
    fun parseRow(closer: Char?): MathNode.Row {
        val items = mutableListOf<MathNode>()
        while (i < src.length) {
            if (closer != null && src[i] == closer) {
                i++
                break
            }
            val atom = parseAtom() ?: continue
            items += withScripts(atom)
        }
        return MathNode.Row(items)
    }

    /** Collects the scripts following [base], in either order, at most one each. */
    private fun withScripts(base: MathNode): MathNode {
        var sub: MathNode? = null
        var sup: MathNode? = null

        while (i < src.length) {
            skipSpaces()
            when {
                i < src.length && src[i] == '^' && sup == null -> {
                    i++
                    sup = parseScriptArgument()
                }

                i < src.length && src[i] == '_' && sub == null -> {
                    i++
                    sub = parseScriptArgument()
                }

                else -> break
            }
        }

        return if (sub == null && sup == null) base else MathNode.Script(base, sub, sup)
    }

    /** A bare script takes one token; a braced one takes the whole group. */
    private fun parseScriptArgument(): MathNode {
        skipSpaces()
        if (i >= src.length) return MathNode.Row(emptyList())
        return parseAtom() ?: MathNode.Row(emptyList())
    }

    /** One unit a script can attach to. Null when the token produced nothing. */
    private fun parseAtom(): MathNode? {
        if (i >= src.length) return null
        val c = src[i]

        return when {
            c.isWhitespace() -> {
                skipSpaces()
                null
            }

            c == '{' -> {
                i++
                parseRow(closer = '}')
            }

            // A closing brace with nothing open: malformed input, so it is
            // dropped rather than allowed to swallow the rest of the formula.
            c == '}' -> {
                i++
                null
            }

            c == '\\' -> parseCommand()

            // Alignment markers from environments. They carry no meaning once
            // the columns are gone, but a space where they were keeps the terms
            // from running together.
            c == '&' -> {
                i++
                MathNode.Space(0.35f)
            }

            c.isDigit() -> {
                val start = i
                while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                MathNode.Sym(src.substring(start, i))
            }

            c.isLetter() -> {
                i++
                MathNode.Sym(c.toString(), italic = true)
            }

            else -> {
                i++
                MathNode.Sym(OPERATORS[c] ?: c.toString())
            }
        }
    }

    private fun parseCommand(): MathNode? {
        i++ // the backslash
        if (i >= src.length) return null

        // An escaped character, or a row break.
        if (!src[i].isLetter()) {
            val c = src[i]
            i++
            return when (c) {
                '\\' -> MathNode.Break
                ',' -> MathNode.Space(0.17f)
                ';' -> MathNode.Space(0.28f)
                '!' -> MathNode.Space(-0.17f)
                else -> MathNode.Sym(c.toString())
            }
        }

        val start = i
        while (i < src.length && src[i].isLetter()) i++
        val name = src.substring(start, i)

        return when (name) {
            "frac", "dfrac", "tfrac", "cfrac" ->
                MathNode.Frac(parseGroupArgument(), parseGroupArgument())

            "sqrt" -> {
                val index = parseOptionalArgument()
                MathNode.Sqrt(parseGroupArgument(), index)
            }

            // Upright words. The content is text, not maths, so it is taken
            // verbatim instead of being parsed a character at a time.
            "text", "textrm", "mathrm", "operatorname", "mathsf", "textbf",
            "mathbf", "boldsymbol", "mathbb", "mathcal",
            -> MathNode.Sym(readGroupText())

            "textit", "mathit", "emph" -> MathNode.Sym(readGroupText(), italic = true)

            // Environments carry a layout we cannot reproduce. Dropping the
            // wrapper keeps the rows, which is the part that means something.
            "begin", "end" -> {
                readGroupText()
                if (name == "begin") null else MathNode.Break
            }

            // Sizing commands: the delimiter that follows is what matters.
            "left", "right", "big", "Big", "bigg", "Bigg", "middle" -> {
                skipSpaces()
                if (i >= src.length) return null
                val delimiter = readDelimiter()
                if (delimiter.isEmpty()) null else MathNode.Sym(delimiter)
            }

            "quad" -> MathNode.Space(1f)
            "qquad" -> MathNode.Space(2f)
            "thinspace" -> MathNode.Space(0.17f)

            in FUNCTIONS -> MathNode.Sym(name)

            else -> {
                val symbol = MATH_SYMBOLS[name]
                when {
                    symbol != null -> MathNode.Sym(symbol)
                    // Unknown, but it may still wrap something worth showing:
                    // an overline or a vector arrow both read fine as their
                    // content. Keeping the argument beats printing a command
                    // the reader never wrote.
                    peekIsGroup() -> parseGroupArgument()
                    else -> null
                }
            }
        }
    }

    /** Braced normally, but TeX allows a bare token and models write that too. */
    private fun parseGroupArgument(): MathNode {
        skipSpaces()
        if (i >= src.length) return MathNode.Row(emptyList())
        return parseAtom() ?: MathNode.Row(emptyList())
    }

    /** The optional index of a root. */
    private fun parseOptionalArgument(): MathNode? {
        skipSpaces()
        if (i >= src.length || src[i] != '[') return null
        i++
        return parseRow(closer = ']')
    }

    /** Reads a braced argument as literal text, for the upright wrappers. */
    private fun readGroupText(): String {
        skipSpaces()
        if (i >= src.length || src[i] != '{') {
            // Unbraced: take a single token so the parser cannot stall.
            if (i < src.length) {
                val c = src[i]
                i++
                return c.toString()
            }
            return ""
        }
        i++
        val start = i
        var depth = 1
        while (i < src.length && depth > 0) {
            when (src[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth > 0) i++
        }
        val text = src.substring(start, i)
        if (i < src.length) i++ // the closing brace
        return text
    }

    /** The delimiter after a sizing command; a full stop means "none". */
    private fun readDelimiter(): String {
        if (src[i] == '\\') {
            i++
            val start = i
            while (i < src.length && src[i].isLetter()) i++
            return MATH_SYMBOLS[src.substring(start, i)] ?: ""
        }
        val c = src[i]
        i++
        return if (c == '.') "" else c.toString()
    }

    private fun peekIsGroup(): Boolean {
        var j = i
        while (j < src.length && src[j].isWhitespace()) j++
        return j < src.length && src[j] == '{'
    }

    private fun skipSpaces() {
        while (i < src.length && src[i].isWhitespace()) i++
    }
}

/** Set upright, like TeX does, so a function name is not read as a product. */
private val FUNCTIONS = setOf(
    "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
    "sinh", "cosh", "tanh", "log", "ln", "lg", "exp", "det", "dim", "gcd",
    "hom", "ker", "deg", "max", "min", "sup", "inf", "lim", "arg", "mod",
)

/** Characters that read better as their typographic form. */
private val OPERATORS = mapOf(
    '-' to "−", // a real minus sign, not a hyphen
    '\'' to "′", // prime
)

/**
 * LaTeX command to the character it stands for.
 *
 * Every value is a plain Unicode codepoint, which is what lets the whole feature
 * work offline with no font shipped: the system font already has these.
 */
internal val MATH_SYMBOLS: Map<String, String> = buildMap {
    putAll(
        listOf(
            "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
            "epsilon" to "ϵ", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
            "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
            "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
            "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ",
            "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ",
            "phi" to "ϕ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ",
            "omega" to "ω",
        )
    )
    putAll(
        listOf(
            "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
            "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
            "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        )
    )

    putAll(
        listOf(
            "times" to "×", "cdot" to "⋅", "div" to "÷", "pm" to "±", "mp" to "∓",
            "ast" to "∗", "star" to "⋆", "circ" to "∘", "bullet" to "∙",
            "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥",
            "neq" to "≠", "ne" to "≠", "approx" to "≈", "equiv" to "≡",
            "sim" to "∼", "simeq" to "≃", "cong" to "≅", "propto" to "∝",
            "ll" to "≪", "gg" to "≫", "subset" to "⊂", "subseteq" to "⊆",
            "supset" to "⊃", "supseteq" to "⊇", "in" to "∈", "notin" to "∉",
            "ni" to "∋", "cup" to "∪", "cap" to "∩", "setminus" to "∖",
            "emptyset" to "∅", "varnothing" to "∅", "infty" to "∞",
            "partial" to "∂", "nabla" to "∇", "forall" to "∀", "exists" to "∃",
            "neg" to "¬", "lnot" to "¬", "land" to "∧", "lor" to "∨",
            "wedge" to "∧", "vee" to "∨", "oplus" to "⊕", "otimes" to "⊗",
            "perp" to "⊥", "parallel" to "∥", "angle" to "∠", "triangle" to "△",
            "degree" to "°", "prime" to "′", "percent" to "%",
        )
    )

    // Big operators. Rendered at text size: a true display-size integral needs
    // glyph scaling that a single codepoint cannot give.
    putAll(
        listOf(
            "sum" to "∑", "prod" to "∏", "coprod" to "∐",
            "int" to "∫", "iint" to "∬", "iiint" to "∭", "oint" to "∮",
            "bigcup" to "⋃", "bigcap" to "⋂",
        )
    )

    putAll(
        listOf(
            "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
            "leftrightarrow" to "↔", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
            "Leftrightarrow" to "⇔", "mapsto" to "↦", "implies" to "⇒",
            "iff" to "⇔", "uparrow" to "↑", "downarrow" to "↓",
        )
    )

    putAll(
        listOf(
            "ldots" to "…", "dots" to "…", "cdots" to "⋯", "vdots" to "⋮",
            "ddots" to "⋱", "langle" to "⟨", "rangle" to "⟩",
            "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉",
            "vert" to "|", "backslash" to "\\",
        )
    )
}
