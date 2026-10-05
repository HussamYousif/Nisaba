package com.nisaba.scribe.layout

import com.nisaba.domain.answer.Block
import com.nisaba.domain.shared.PageRect
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private const val CODE_SCALE = 0.8f       // code is written a little smaller than prose
private const val CODE_LINE = 2.5f        // line pitch, in code x-heights (room for brackets and descenders)
private const val PAD_X = 0.8f
private const val PAD_Y = 0.6f
private const val BASELINE_IN_LINE = 1.8f
private const val MIN_COLUMNS = 12
private const val CONTINUATION_INDENT = 2 // a wrapped line goes on two cells in, after a small hook
private const val CELL_FILL = 0.92f      // a glyph wider than this share of its cell is shrunk to fit

/** One written row of code: its text, the tone of each character, and whether it continues the line above. */
private class CodeRow(val text: String, val tones: List<InkTone>, val continued: Boolean)

/** Splits source lines into rows of at most [cols] cells; a continuation row is indented and marked. */
private fun codeRows(lines: List<String>, tones: List<Array<InkTone>>, cols: Int): List<CodeRow> = lines.mapIndexed { n, line ->
    val t = tones.getOrNull(n)?.toList() ?: List(line.length) { InkTone.Plain }
    if (line.length <= cols) return@mapIndexed listOf(CodeRow(line, t, false))
    val rows = arrayListOf(CodeRow(line.take(cols), t.take(cols), false))
    val room = (cols - CONTINUATION_INDENT).coerceAtLeast(1)
    var at = cols
    val indent = List(CONTINUATION_INDENT) { InkTone.Plain }
    while (at < line.length) {
        val end = (at + room).coerceAtMost(line.length)
        rows += CodeRow(" ".repeat(CONTINUATION_INDENT) + line.substring(at, end), indent + t.subList(at, end), true)
        at = end
    }
    rows
}.flatten()

/** Runs of non-space characters of one tone, with the cell they start in. */
private fun tokens(row: CodeRow): List<Triple<Int, String, InkTone>> {
    val out = ArrayList<Triple<Int, String, InkTone>>()
    var i = 0
    while (i < row.text.length) {
        if (row.text[i].isWhitespace()) { i++; continue }
        val tone = row.tones.getOrElse(i) { InkTone.Plain }
        var j = i + 1
        while (j < row.text.length && !row.text[j].isWhitespace() && row.tones.getOrElse(j) { InkTone.Plain } == tone) j++
        out += Triple(i, row.text.substring(i, j), tone); i = j
    }
    return out
}

/**
 * A code block: a ruled box, then the code written on a monospace grid (every character in a cell of the same width,
 * so indentation and alignment survive). Letters are not joined. Long lines wrap with a small hook; a block taller
 * than the page breaks between rows and continues in a new box.
 */
internal fun layoutCode(b: Block.Code, cursor: LayoutCursor, ctx: Ctx): Pair<LaidBlock, LayoutCursor> {
    if (b.lines.isEmpty()) return LaidBlock(emptyList(), emptyList(), null) to cursor
    var xh = ctx.region.xHeight * CODE_SCALE
    val inner = ctx.region.width - 2 * PAD_X * xh
    val unit = ctx.set.resolve('n')?.adv ?: 1f
    if (inner / (unit * xh) < MIN_COLUMNS) xh = inner / (MIN_COLUMNS * unit) // a narrow spot: write smaller, not past the edge
    val cell = unit * xh
    val cols = max(1, floor(inner / cell).toInt())
    val rows = codeRows(b.lines, highlightCode(b.lines, b.language), cols)
    val widest = rows.maxOf { it.text.length }
    val boxW = min(ctx.region.width, widest * cell + 2 * PAD_X * xh)
    val left = ctx.region.left
    val lineH = CODE_LINE * xh

    val words = ArrayList<LaidWord>(); val paths = ArrayList<LaidPath>()
    var lineNo = cursor.lineCount; var wordNo = cursor.wordCount
    val wanted = min(rows.size * lineH + 2 * PAD_Y * xh, ctx.region.bottom - ctx.region.pageTop) // whole block if it fits a page
    var (page, top) = findBox(cursor.page, cursor.lineTop, left, wanted, ctx)
    var boxTop = top
    top += PAD_Y * xh

    fun closeBox() {
        val bottom = top + PAD_Y * xh
        val right = left + boxW
        paths += LaidPath(page, floatArrayOf(left, right, right, left, left), floatArrayOf(boxTop, boxTop, bottom, bottom, boxTop))
    }

    for (row in rows) {
        if (top + lineH + PAD_Y * xh > ctx.region.bottom && top > ctx.region.pageTop + PAD_Y * xh) {
            closeBox()
            page++; top = ctx.region.pageTop; boxTop = top; top += PAD_Y * xh
        }
        val baseline = top + BASELINE_IN_LINE * xh
        val x0 = left + PAD_X * xh
        if (row.continued) paths += hook(page, x0, baseline, cell, xh)
        for ((col, text, tone) in tokens(row)) {
            val chars = text.mapIndexedNotNull { i, raw ->
                val c = if (ctx.set.resolve(raw) != null) raw else '?'
                val g = ctx.set.resolve(c) ?: return@mapIndexedNotNull null
                val k = min(charScale(c, ctx.set), cell * CELL_FILL / max(g.adv * xh, 1e-3f))
                PlacedChar(c, g, i * cell + (cell - g.adv * xh * k) / 2f, k)
            }
            if (chars.isEmpty()) continue
            val x = x0 + col * cell
            words += LaidWord(page, x, baseline, xh, chars, text.length * cell, false, lineNo, wordNo++, joinable = false, tone = tone)
        }
        lineNo++
        top += lineH
    }
    closeBox()
    val bottom = top + PAD_Y * xh
    return LaidBlock(words, paths, PageRect(left, boxTop, left + boxW, bottom), pathsFirst = true) to
        LayoutCursor(page, bottom + ctx.region.lineHeight * 0.5f, lineNo, wordNo)
}

/** The ↳ that marks a wrapped line, drawn in the indent cells. */
private fun hook(page: Int, x0: Float, baseline: Float, cell: Float, xh: Float): LaidPath {
    val x = x0 + cell * 0.4f
    val top = baseline - xh * 0.9f
    val end = x0 + cell * (CONTINUATION_INDENT - 0.3f)
    val y = baseline - xh * 0.3f
    val barb = xh * 0.25f; val rise = xh * 0.2f
    return LaidPath(page, floatArrayOf(x, x, end, end - barb, end, end - barb), floatArrayOf(top, y, y, y - rise, y, y + rise))
}
