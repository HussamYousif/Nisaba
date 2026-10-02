package com.nisaba.domain.answer

private const val OPEN_ANSWER = "<answer>"
private const val CLOSE_ANSWER = "</answer>"
private const val SILENT = "<silent/>"
private const val OPEN_HEARD = "<heard>"
private const val CLOSE_HEARD = "</heard>"

private val LIST_RE = Regex("""^(\s*)([-*•]|\d+[.)])\s+(.*)$""")
private val HEADING_RE = Regex("""^\s*(#{1,3})\s+(.*)$""")
private val SEPARATOR_RE = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
private val LABEL_RE = Regex("""^(.+?)\s*@\s*(\d+)\s*,\s*(\d+)\s*$""")

/**
 * Incremental parser for the model's reply (DESIGN §5.2). One instance per request.
 * Feed chunks as they stream in; blocks are reported as soon as they close, so the hand can start writing early.
 * Chunk boundaries never change the result.
 */
class AnswerParser {
    private enum class Phase { Start, InAnswer, AfterAnswer, Done }

    private val buf = StringBuilder()
    private var pos = 0
    private var phase = Phase.Start
    private var heardEmitted = false
    private val blocks = BlockSplitter()

    fun feed(chunk: String): List<ParseEvent> {
        buf.append(chunk)
        return run(final = false)
    }

    /** Call once when the stream ends; flushes everything still pending. */
    fun finish(): List<ParseEvent> = run(final = true)

    private fun run(final: Boolean): List<ParseEvent> {
        val out = ArrayList<ParseEvent>()
        var progressed = true
        while (progressed && phase != Phase.Done) {
            progressed = when (phase) {
                Phase.Start -> start(out, final)
                Phase.InAnswer -> inAnswer(out, final)
                Phase.AfterAnswer -> afterAnswer(out, final)
                Phase.Done -> false
            }
        }
        if (final && !heardEmitted) { heardEmitted = true; out += ParseEvent.Heard("") }
        return out
    }

    private fun rest() = buf.substring(pos)

    private fun start(out: MutableList<ParseEvent>, final: Boolean): Boolean {
        val r = rest().trimStart()
        val skipped = rest().length - r.length
        when {
            r.isEmpty() -> { phase = if (final) Phase.Done else phase; return false }
            r.startsWith(SILENT) -> { pos += skipped + SILENT.length; out += ParseEvent.Silent; phase = Phase.AfterAnswer }
            r.startsWith(OPEN_ANSWER) -> { pos += skipped + OPEN_ANSWER.length; phase = Phase.InAnswer }
            !final && (SILENT.startsWith(r) || OPEN_ANSWER.startsWith(r)) -> return false // tag still arriving
            else -> { pos += skipped; phase = Phase.InAnswer }                              // model forgot <answer>
        }
        return true
    }

    private fun inAnswer(out: MutableList<ParseEvent>, final: Boolean): Boolean {
        val text = rest()
        val term = listOf(CLOSE_ANSWER, OPEN_HEARD).map { it to text.indexOf(it) }.filter { it.second >= 0 }.minByOrNull { it.second }
        if (term != null) {
            emitLines(text.substring(0, term.second), flushTail = true, out)
            pos += term.second + (if (term.first == CLOSE_ANSWER) CLOSE_ANSWER.length else 0)
            out += blocks.flush()
            phase = Phase.AfterAnswer
            return true
        }
        val hold = heldBackSuffix(text)
        val safe = text.length - hold
        val lastNl = text.lastIndexOf('\n', safe - 1)
        if (lastNl >= 0) {
            emitLines(text.substring(0, lastNl + 1), flushTail = false, out)
            pos += lastNl + 1
        }
        if (final) {
            emitLines(rest(), flushTail = true, out); pos = buf.length
            out += blocks.flush()
            phase = Phase.Done
        }
        return false
    }

    /** Length of a trailing fragment that might be the start of a terminator tag. */
    private fun heldBackSuffix(text: String): Int {
        var best = 0
        for (tag in listOf(CLOSE_ANSWER, OPEN_HEARD)) {
            for (k in minOf(tag.length - 1, text.length) downTo 1) {
                if (text.endsWith(tag.substring(0, k))) { best = maxOf(best, k); break }
            }
        }
        return best
    }

    private fun emitLines(chunk: String, flushTail: Boolean, out: MutableList<ParseEvent>) {
        if (chunk.isEmpty()) return
        val lines = chunk.split('\n')
        val complete = if (flushTail) lines else lines.dropLast(1)
        for ((i, l) in complete.withIndex()) {
            if (flushTail && i == complete.lastIndex && l.isEmpty()) continue
            out += blocks.line(l.trimEnd('\r'))
        }
    }

    private fun afterAnswer(out: MutableList<ParseEvent>, final: Boolean): Boolean {
        val text = rest()
        val open = text.indexOf(OPEN_HEARD)
        if (open < 0) { phase = if (final) Phase.Done else phase; return false }
        val from = open + OPEN_HEARD.length
        val close = text.indexOf(CLOSE_HEARD, from)
        if (close < 0) {
            if (final) { out += ParseEvent.Heard(text.substring(from).trim()); heardEmitted = true; pos = buf.length; phase = Phase.Done }
            return false
        }
        out += ParseEvent.Heard(text.substring(from, close).trim()); heardEmitted = true
        pos += close + CLOSE_HEARD.length
        phase = Phase.Done
        return true
    }
}

/** Line-oriented block builder: reports a block only once it can no longer grow. */
private class BlockSplitter {
    private val para = ArrayList<String>()
    private var list: ListBuilder? = null
    private val table = ArrayList<String>()
    private var fence: ArrayList<String>? = null
    private var math: ArrayList<String>? = null

    private class ListBuilder(val ordered: Boolean, val marker: String, val depth: Int, var text: String)

    fun line(l: String): List<ParseEvent> {
        val out = ArrayList<ParseEvent>()
        val t = l.trim()
        if (!inOpenContainer(l, t, out)) closingPass(l, t, out)
        return out
    }

    private fun emit(out: MutableList<ParseEvent>, b: Block?) { if (b != null) out += ParseEvent.BlockReady(b) }

    /** Fence and display-math bodies swallow lines verbatim until their closer. Returns true if [l] was consumed. */
    private fun inOpenContainer(l: String, t: String, out: MutableList<ParseEvent>): Boolean {
        fence?.let { f ->
            if (t == "```") { emit(out, diagram(f)); fence = null } else f += l
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
            t.startsWith("```") -> { emit(out, closePara()); fence = ArrayList() }
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
        fence?.let { emit(closeAsParagraph(it)); fence = null }
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

    private fun diagram(lines: List<String>): Block? {
        val body = lines.joinToString("\n")
        val end = body.indexOf("</svg>")
        if (end < 0) return closeAsParagraph(lines)
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
fun parseInlines(text: String): List<Inline> {
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
