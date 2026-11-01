package com.nisaba.domain.answer

private val GREEK = setOf(
    "alpha", "beta", "gamma", "delta", "epsilon", "varepsilon", "zeta", "eta", "theta", "vartheta", "iota", "kappa", "lambda", "mu",
    "nu", "xi", "pi", "varpi", "rho", "sigma", "tau", "upsilon", "phi", "varphi", "chi", "psi", "omega",
    "Gamma", "Delta", "Theta", "Lambda", "Xi", "Pi", "Sigma", "Upsilon", "Phi", "Psi", "Omega",
)

private val ALIASES = mapOf(
    "to" to "to", "le" to "le", "ge" to "ge", "ne" to "ne", "leq" to "le", "geq" to "ge", "neq" to "ne",
    "rightarrow" to "to", "longrightarrow" to "to", "Rightarrow" to "implies",
    "leftarrow" to "leftarrow", "infty" to "infty", "cdot" to "cdot", "times" to "times", "div" to "div", "pm" to "pm", "mp" to "mp",
    "approx" to "approx", "equiv" to "equiv", "ldots" to "ldots", "cdots" to "ldots", "dots" to "ldots", "partial" to "partial",
    "nabla" to "nabla", "propto" to "propto", "in" to "in", "subset" to "subset", "forall" to "forall", "exists" to "exists",
    "degree" to "degree", "circ" to "degree", "angle" to "angle", "perp" to "perp", "sim" to "sim", "lt" to "<", "gt" to ">",
    "lfloor" to "lfloor", "rfloor" to "rfloor", "lceil" to "lceil", "rceil" to "rceil",
)

private val BIG_OPS = mapOf("sum" to "sum", "prod" to "prod", "int" to "int", "oint" to "int", "lim" to "lim")
/** \sin x, \ln W: names written as words (never with their backslash). */
private val FUNCTIONS = setOf(
    "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "coth",
    "log", "ln", "lg", "exp", "det", "dim", "ker", "deg", "arg", "gcd", "max", "min", "sup", "inf", "Pr",
)
private val SPACING = setOf(",", ";", ":", "!", " ", "quad", "qquad")
private val LITERAL_ESCAPES = setOf('%', '$', '&', '#', '_', '{', '}')
private val MATRIX_ENVS = mapOf("pmatrix" to ("(" to ")"), "bmatrix" to ("[" to "]"), "matrix" to ("" to ""), "vmatrix" to ("|" to "|"))
private val FRACTIONS = setOf("frac", "dfrac", "tfrac")
private val TEXT_COMMANDS = setOf("text", "mathrm", "textbf", "mathbf", "operatorname")
private val DELIMITER_NAMES = mapOf(
    "|" to "||", "langle" to "<", "rangle" to ">", "lbrace" to "{", "rbrace" to "}",
    "lfloor" to "[", "rfloor" to "]", "lceil" to "[", "rceil" to "]", // a stretched fence: square brackets come closest
)
private const val MAX_DEPTH = 24

/** LaTeX subset → [MathNode]. Never throws: malformed input yields a best-effort tree, unknown commands become text. */
class MathParser(private val src: String) {
    private var i = 0
    private var depth = 0

    fun parse(): MathNode {
        val parts = ArrayList<MathNode>()
        while (true) {
            parts += row(stopAt = { false })
            if (i >= src.length) break
            i++ // a stray '}' ended the row early: skip it, keep the rest
        }
        return finish(parts.flatMap { if (it is MathNode.Row) it.items else listOf(it) })
    }

    private fun peek(): Char? = src.getOrNull(i)
    private fun startsWith(s: String) = src.startsWith(s, i)

    private fun row(stopAt: () -> Boolean): MathNode {
        val items = ArrayList<MathNode>()
        while (i < src.length && !stopAt()) {
            val c = peek()!!
            when {
                c.isWhitespace() -> i++
                c == '}' -> return finish(items)                                         // unmatched closer: end this group
                else -> { val atom = atom() ?: continue; items += scripts(atom) }
            }
        }
        return finish(items)
    }

    private fun finish(items: List<MathNode>): MathNode = if (items.size == 1) items[0] else MathNode.Row(items)

    /** Attaches any `^` / `_` that follow [base]. */
    private fun scripts(base: MathNode): MathNode {
        var sub: MathNode? = null; var sup: MathNode? = null
        while (true) {
            skipSpace()
            when (peek()) {
                '^' -> { i++; sup = scriptArg() }
                '_' -> { i++; sub = scriptArg() }
                else -> break
            }
        }
        return when {
            base is MathNode.BigOp && (sub != null || sup != null) -> base.copy(lower = sub, upper = sup)
            sub != null && sup != null -> MathNode.SubSup(base, sub, sup)
            sup != null -> MathNode.Sup(base, sup)
            sub != null -> MathNode.Sub(base, sub)
            else -> base
        }
    }

    private fun scriptArg(): MathNode { skipSpace(); return atom() ?: MathNode.EMPTY }

    private fun skipSpace() { while (i < src.length && src[i].isWhitespace()) i++ }

    private fun atom(): MathNode? {
        val c = peek() ?: return null
        return when (c) {
            '{' -> group()
            '\\' -> command()
            '^', '_' -> { i++; null }                                                    // dangling script marker
            else -> { i++; MathNode.Sym(c.toString()) }
        }
    }

    private fun group(): MathNode {
        i++ // {
        if (++depth > MAX_DEPTH) { depth--; return MathNode.EMPTY }
        val inner = row(stopAt = { false })
        if (peek() == '}') i++
        depth--
        return inner
    }

    private fun braced(): MathNode { skipSpace(); return if (peek() == '{') group() else atom() ?: MathNode.EMPTY }

    private fun name(): String {
        val start = i
        while (i < src.length && src[i].isLetter()) i++
        return src.substring(start, i)
    }

    private fun command(): MathNode? {
        i++ // backslash
        val c = peek() ?: return MathNode.Sym("\\")
        if (!c.isLetter()) { i++; return symbolic(c) }
        val cmd = name()
        return when (cmd) {
            in FRACTIONS -> MathNode.Frac(braced(), braced())
            "sqrt" -> sqrt()
            "left" -> fenced()
            "right" -> { skipDelimiter(); null }
            "begin" -> environment()
            in TEXT_COMMANDS -> MathNode.Text(rawText())
            else -> symbolCommand(cmd)
        }
    }

    private fun symbolCommand(cmd: String): MathNode? = when {
        cmd in BIG_OPS -> MathNode.BigOp(BIG_OPS.getValue(cmd), null, null)
        cmd in FUNCTIONS -> MathNode.Text(cmd, function = true)
        cmd in GREEK -> MathNode.Sym(cmd)
        cmd in ALIASES -> MathNode.Sym(ALIASES.getValue(cmd))
        cmd in SPACING -> null
        else -> MathNode.Text("\\$cmd")                                                    // unknown: written literally
    }

    private fun symbolic(c: Char): MathNode? = when {
        c in SPACING.map { it[0] } -> null
        c == '\\' -> MathNode.Sym("\\\\")
        c in LITERAL_ESCAPES -> MathNode.Sym(c.toString())
        c == '|' -> MathNode.Sym("||")
        else -> MathNode.Text("\\$c")
    }

    private fun sqrt(): MathNode {
        skipSpace()
        var index: MathNode? = null
        if (peek() == '[') {
            i++
            val start = i
            while (i < src.length && src[i] != ']') i++
            index = MathParser(src.substring(start, i)).parse()
            if (peek() == ']') i++
        }
        return MathNode.Sqrt(index, braced())
    }

    private fun rawText(): String {
        skipSpace()
        if (peek() != '{') return ""
        i++
        val start = i
        var level = 1
        while (i < src.length && level > 0) { if (src[i] == '{') level++ else if (src[i] == '}') level--; i++ }
        return src.substring(start, if (level == 0) i - 1 else i)
    }

    private fun skipDelimiter() {
        skipSpace()
        if (peek() == '\\') { i++; if (peek()?.isLetter() == true) name() else i++ } else if (i < src.length) i++
    }

    private fun readDelimiter(): String {
        skipSpace()
        val c = peek() ?: return ""
        if (c == '.') { i++; return "" }
        if (c == '\\') {
            i++
            val n = if (peek()?.isLetter() == true) name() else peek()?.also { i++ }?.toString().orEmpty()
            return DELIMITER_NAMES[n] ?: n
        }
        i++
        return c.toString()
    }

    private fun fenced(): MathNode {
        val left = readDelimiter()
        if (++depth > MAX_DEPTH) { depth--; return MathNode.EMPTY }
        val body = row(stopAt = { startsWith("\\right") })
        var right = ""
        if (startsWith("\\right")) { i += "\\right".length; right = readDelimiter() }
        depth--
        return MathNode.Fenced(left, body, right)
    }

    private fun environment(): MathNode {
        val env = rawText()
        val (open, close) = MATRIX_ENVS[env] ?: return MathNode.Text("\\begin{$env}")
        val rows = ArrayList<List<MathNode>>()
        var cells = ArrayList<MathNode>()
        val end = "\\end{$env}"
        if (++depth > MAX_DEPTH) { depth--; return MathNode.EMPTY }
        var parts = ArrayList<MathNode>()          // pieces of the current cell (a stray '}' splits one)
        fun endCell() { cells += finish(parts.flatMap { if (it is MathNode.Row) it.items else listOf(it) }); parts = ArrayList() }
        while (i < src.length && !startsWith(end)) {
            parts += row(stopAt = { startsWith("&") || startsWith("\\\\") || startsWith(end) })
            when {
                startsWith("&") -> { endCell(); i++ }
                startsWith("\\\\") -> { endCell(); i += 2; rows += cells; cells = ArrayList() }
                startsWith(end) || i >= src.length -> endCell()
                else -> i++ // a stray '}' stopped the cell: skip it, the cell carries on
            }
        }
        if (cells.isNotEmpty()) rows += cells
        if (startsWith(end)) i += end.length
        depth--
        return MathNode.Matrix(rows, open, close)
    }
}

fun parseMath(latex: String): MathNode = MathParser(latex).parse()
