package com.nisaba.domain.answer


// Inside <answer>: lines become blocks (paragraphs, lists, tables, math, code, diagrams) and inline runs.

private val LIST_RE = Regex("""^(\s*)([-*•]|\d+[.)])\s+(.*)$""")
private val HEADING_RE = Regex("""^\s*(#{1,3})\s+(.*)$""")
private val SEPARATOR_RE = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
private val LABEL_RE = Regex("""^(.+?)\s*@\s*(\d+)\s*,\s*(\d+)\s*$""")

/** Line-oriented block builder: reports a block only once it can no longer grow. */
internal class BlockSplitter {
    private val para = ArrayList<String>()
    private var list: ListBuilder? = null
    private val table = ArrayList<String>()
    private var fence: ArrayList<String>? = null
    private var fenceLanguage = ""
    private var math: ArrayList<String>? = null

    private class ListBuilder(val ordered: Boolean, val marker: String, val depth: Int, var text: String)

    fun line(l: String): List<ParseEvent> {
        val out = ArrayList<ParseEvent>()
        val t = l.trim()
        val mark = if (fence == null && math == null) parseMark(t) else null
        when {
            mark != null -> { out += flush(); emit(out, mark) }
            !inOpenContainer(l, t, out) && !LONE_TAG_RE.matches(t) -> closingPass(l, t, out)
        }
        return out
    }

    private fun emit(out: MutableList<ParseEvent>, b: Block?) { if (b != null) out += ParseEvent.BlockReady(b) }

    /** Fence and display-math bodies swallow lines verbatim until their closer. Returns true if [l] was consumed. */
    private fun inOpenContainer(l: String, t: String, out: MutableList<ParseEvent>): Boolean {
        fence?.let { f ->
            if (t == "```") { emit(out, closeFence(f)); fence = null } else f += l
            return true
        }
        math?.let { m ->
            if (t.contains("$$")) {
                m += t.substringBefore("$$")
                emit(out, Block.MathBlock(m.joinToString(" ").trim()))
                math = null
            } else {
                m += t
            }
            return true
        }
        return false
    }

    /** Closes whatever the new line can no longer extend, then classifies the line itself. */
    private fun closingPass(l: String, t: String, out: MutableList<ParseEvent>) {
        if (table.isNotEmpty() && !t.startsWith("|")) emit(out, closeTable())
        val lb = list
        if (lb != null) {
            val continuation = l.startsWith("  ") && t.isNotEmpty() && LIST_RE.matchEntire(l) == null && !t.startsWith("|")
            if (continuation) { lb.text += " " + t; return }
            emit(out, closeList())
        }
        classify(l, t, out)
    }

    private fun classify(l: String, t: String, out: MutableList<ParseEvent>) {
        val heading = HEADING_RE.matchEntire(l)
        val item = LIST_RE.matchEntire(l)
        when {
            t.isEmpty() -> emit(out, closePara())
            t.startsWith("```") -> { emit(out, closePara()); fence = ArrayList(); fenceLanguage = t.removePrefix("```").trim() }
            t.startsWith("$$") -> { emit(out, closePara()); openMath(t, out) }
            heading != null -> {
                emit(out, closePara())
                emit(out, Block.Heading(heading.groupValues[1].length, parseInlines(heading.groupValues[2].trim())))
            }
            t.startsWith("|") -> { emit(out, closePara()); table += t }
            t == "---" || t == "***" -> { emit(out, closePara()); emit(out, Block.Rule) }
            item != null -> {
                emit(out, closePara())
                val marker = item.groupValues[2]
                list = ListBuilder(marker.first().isDigit(), marker, item.groupValues[1].length / 2, item.groupValues[3].trim())
            }
            else -> para += t
        }
    }

    private fun openMath(t: String, out: MutableList<ParseEvent>) {
        val inner = t.removePrefix("$$")
        if (inner.contains("$$")) emit(out, Block.MathBlock(inner.substringBefore("$$").trim())) else math = arrayListOf(inner)
    }

    fun flush(): List<ParseEvent> {
        val out = ArrayList<ParseEvent>()
        fun emit(b: Block?) { if (b != null) out += ParseEvent.BlockReady(b) }
        emit(closeList()); emit(closeTable()); emit(closePara())
        fence?.let { emit(closeFence(it)); fence = null } // cut off mid-block: draw what arrived, never write out raw SVG
        math?.let { emit(Block.MathBlock(it.joinToString(" ").trim())); math = null }
        return out
    }

    private fun closePara(): Block? {
        if (para.isEmpty()) return null
        val b = Block.Paragraph(parseInlines(para.joinToString(" ")))
        para.clear(); return b
    }

    private fun closeList(): Block? = list?.let {
        list = null
        Block.ListItem(it.ordered, it.marker, it.depth, parseInlines(it.text))
    }

    private fun closeAsParagraph(lines: List<String>): Block? =
        lines.joinToString(" ").trim().takeIf { it.isNotEmpty() }?.let { Block.Paragraph(parseInlines(it)) }

    /** A table needs a header separator row; anything else degrades to plain paragraphs. */
    private fun closeTable(): Block? {
        if (table.isEmpty()) return null
        val rows = table.toList(); table.clear()
        if (rows.size >= 2 && SEPARATOR_RE.matches(rows[1])) {
            return Block.Table(splitRow(rows[0]).map(::parseInlines), rows.drop(2).map { r -> splitRow(r).map(::parseInlines) })
        }
        return Block.Paragraph(parseInlines(rows.joinToString(" ")))
    }

    private fun splitRow(row: String): List<String> = row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** ```diagram (or anything holding an <svg>) is a drawing; any other fence is code. */
    private fun closeFence(lines: List<String>): Block? {
        val drawing = fenceLanguage.equals("diagram", ignoreCase = true) || fenceLanguage.equals("svg", ignoreCase = true) ||
            lines.any { "<svg" in it }
        if (drawing) return diagram(lines)
        val body = lines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.map { it.trimEnd().replace("\t", "    ") }
        return if (body.isEmpty()) null else Block.Code(fenceLanguage, body)
    }

    private fun diagram(lines: List<String>): Block? {
        val body = lines.joinToString("\n")
        val end = body.indexOf("</svg>")
        if (end < 0) {
            val open = body.indexOf("<svg")
            if (open < 0) return closeAsParagraph(lines)
            val complete = body.substring(open, body.lastIndexOf('>') + 1) // drop a half-sent element
            return Block.Diagram("$complete</svg>", emptyList())
        }
        val svg = body.substring(0, end + "</svg>".length).trim()
        val labels = body.substring(end + "</svg>".length).lines().mapNotNull { l ->
            LABEL_RE.matchEntire(l.trim())?.let { m ->
                DiagramLabel(m.groupValues[1].trim(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }
        }
        return Block.Diagram(svg, labels)
    }
}

/** `**bold**`, `$math$`; other markdown markers (`*`, backticks) are dropped: the hand has one style. */
fun parseInlines(raw: String): List<Inline> {
    val text = scriptsToMath(raw)
    val out = ArrayList<Inline>()
    val plain = StringBuilder()
    fun flushPlain() { if (plain.isNotEmpty()) { out += Inline.Text(plain.toString()); plain.clear() } }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            text.startsWith("**", i) && text.indexOf("**", i + 2) > 0 -> {
                val end = text.indexOf("**", i + 2)
                flushPlain(); out += Inline.Text(text.substring(i + 2, end), bold = true); i = end + 2
            }
            c == '$' && !text.startsWith("$$", i) -> {
                val end = text.indexOf('$', i + 1)
                if (end > i + 1 && !text[i + 1].isWhitespace() && !text[end - 1].isWhitespace()) {
                    flushPlain(); out += Inline.Math(text.substring(i + 1, end)); i = end + 1
                } else { plain.append(c); i++ }
            }
            c == '*' || c == '`' -> i++
            else -> { plain.append(c); i++ }
        }
    }
    flushPlain()
    return out.ifEmpty { listOf(Inline.Text("")) }.let { mergeText(it) }
}

private fun mergeText(items: List<Inline>): List<Inline> {
    val out = ArrayList<Inline>()
    for (it in items) {
        val last = out.lastOrNull()
        if (it is Inline.Text && last is Inline.Text && it.bold == last.bold) {
            out[out.lastIndex] = Inline.Text(last.s + it.s, it.bold)
        } else {
            out += it
        }
    }
    return out
}

private const val SUB_CHARS = "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎"
private const val SUP_CHARS = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱ"
private const val SUB_PLAIN = "0123456789+-=()"
private const val SUP_PLAIN = "0123456789+-=()ni"
private val SCRIPT_WORD = Regex("[\\p{L}\\p{N}]*[$SUB_CHARS$SUP_CHARS]+[\\p{L}\\p{N}$SUB_CHARS$SUP_CHARS]*")

/**
 * H₂O, x², 10⁻³: Unicode sub- and superscripts the hand has no glyphs for (they came out as "?"), rewritten as inline
 * math it can write ($H_{2}O$). Words already inside $…$ are left alone.
 */
fun scriptsToMath(text: String): String {
    if (text.none { it in SUB_CHARS || it in SUP_CHARS }) return text
    val math = Regex("""\$[^$]*\$""").findAll(text).map { it.range }.toList()
    return SCRIPT_WORD.replace(text) { m ->
        if (math.any { m.range.first in it }) return@replace m.value
        val sb = StringBuilder("$")
        var i = 0
        val w = m.value
        while (i < w.length) {
            val sub = SUB_CHARS.indexOf(w[i]); val sup = SUP_CHARS.indexOf(w[i])
            if (sub < 0 && sup < 0) { sb.append(w[i]); i++; continue }
            val chars = if (sub >= 0) SUB_CHARS else SUP_CHARS
            val plain = if (sub >= 0) SUB_PLAIN else SUP_PLAIN
            sb.append(if (sub >= 0) "_{" else "^{")
            while (i < w.length && chars.indexOf(w[i]) >= 0) { sb.append(plain[chars.indexOf(w[i])]); i++ }
            sb.append('}')
        }
        sb.append('$').toString()
    }
}

private val MARK_RE = Regex("""^<\s*mark\b([^>]*?)/?\s*>(.*?)(<\s*/\s*mark\s*>)?$""", RegexOption.IGNORE_CASE)
private val LINE_ATTR = Regex("""line\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE)
private val OK_ATTR = Regex("""ok\s*=\s*["']?(\w+)""", RegexOption.IGNORE_CASE)

/** `<mark line="2" ok="false">sign error</mark>` (or self-closing, attributes in any order) → a mark; anything else null. */
internal fun parseMark(t: String): Block.Mark? {
    val m = MARK_RE.matchEntire(t) ?: return null
    val line = LINE_ATTR.find(m.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val ok = OK_ATTR.find(m.groupValues[1])?.groupValues?.get(1)?.lowercase() in setOf("true", "yes", "1")
    return Block.Mark(line, ok, m.groupValues[2].trim())
}
