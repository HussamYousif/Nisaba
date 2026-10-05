package com.nisaba.scribe.layout

import com.nisaba.domain.answer.Block
import com.nisaba.domain.shared.PageRect
import kotlin.math.max
import kotlin.math.min

private const val CELL_SCALE = 0.85f
private const val PAD_X = 0.6f
private const val PAD_Y = 0.35f
private const val CELL_LINE = 1.9f        // line pitch inside a cell, in cell x-heights
private const val MIN_COL = 3f            // never squeeze a column below this many cell x-heights
private const val DOUBLE_RULE_GAP = 0.18f
private const val BASELINE_IN_LINE = 1.35f
private const val MIN_SQUEEZE = 0.6f

/** A word in a cell: its glyphs, its width, and its scale (below the cell's x-height when squeezed into a narrow column). */
private class CellWord(val spec: WordSpec, val chars: List<PlacedChar>, val width: Float, val scale: Float)

private class CellLine(val words: List<CellWord>)

private class Cell(val lines: List<CellLine>) {
    val lineCount: Int get() = max(1, lines.size)
}

private class Row(val cells: List<Cell>, val heightUnits: Float, val header: Boolean)

private fun wordWidth(spec: WordSpec, ctx: Ctx, xh: Float): Float =
    if (spec.math != null) inlineMath(spec.math, ctx.set, xh).second else measure(spec.text, ctx.set, xh).second

/**
 * Column widths for a table that may be wider than the page: every column keeps at least [minimum] (its longest
 * unbreakable word) and the remaining room goes to the columns that can wrap, in proportion to how much they can shrink.
 */
internal fun fitColumns(natural: List<Float>, minimum: List<Float>, available: Float): List<Float> {
    if (natural.sum() <= available) return natural
    val floor = minimum.mapIndexed { i, m -> min(m, natural[i]) }
    if (floor.sum() >= available) return floor.map { it * available / floor.sum() }         // even the words don't fit: squeeze evenly
    val spare = available - floor.sum()
    val wrappable = natural.indices.map { natural[it] - floor[it] }
    val total = wrappable.sum().takeIf { it > 0f } ?: return floor
    return natural.indices.map { floor[it] + spare * (wrappable[it] / total).coerceAtMost(1f) }.let { w ->
        val over = w.sum() / available
        if (over > 1f) w.map { it / over } else w
    }
}

/** Natural (unwrapped) width of a cell, in px, at scale [xh]. */
private fun naturalWidth(specs: List<WordSpec>, ctx: Ctx, xh: Float, space: Float): Float {
    var w = 0f
    for ((i, spec) in specs.withIndex()) {
        w += (if (spec.math != null) inlineMath(spec.math, ctx.set, xh).second else measure(spec.text, ctx.set, xh).second)
        if (i < specs.lastIndex) w += space
    }
    return w
}

/** Wraps a cell to [width]; a word wider than the column is squeezed, and broken if squeezing is not enough. */
private fun wrapCell(specs: List<WordSpec>, ctx: Ctx, xh: Float, space: Float, width: Float): Cell {
    val lines = ArrayList<CellLine>()
    var current = ArrayList<CellWord>()
    var used = 0f
    for (spec in breakOverlong(specs, ctx.set, xh, width)) {
        val measured = if (spec.math != null) inlineMath(spec.math, ctx.set, xh) else measure(spec.text, ctx.set, xh)
        val k = if (measured.second > width) (width / measured.second).coerceAtLeast(MIN_SQUEEZE) else 1f
        val w = measured.second * k
        if (current.isNotEmpty() && used + space + w > width) { lines += CellLine(current); current = ArrayList(); used = 0f }
        current += CellWord(spec, measured.first, w, xh * k)
        used += (if (used > 0f) space else 0f) + w
    }
    if (current.isNotEmpty()) lines += CellLine(current)
    return Cell(lines)
}

private fun cellSpecs(r: List<List<WordSpec>>, c: Int) = r.getOrElse(c) { emptyList() }

/** Each column as wide as its content, squeezed by [fitColumns] when the table is wider than the region. */
private fun columnWidths(rows: List<List<List<WordSpec>>>, cols: Int, ctx: Ctx, xh: Float, space: Float): List<Float> {
    val natural = List(cols) { c -> rows.maxOf { naturalWidth(cellSpecs(it, c), ctx, xh, space) } + 2 * PAD_X * xh }
    val longestWord = List(cols) { c ->
        rows.maxOf { r -> cellSpecs(r, c).maxOfOrNull { wordWidth(it, ctx, xh) } ?: 0f } + 2 * PAD_X * xh
    }
    return fitColumns(natural, longestWord.map { max(it, (MIN_COL + 2 * PAD_X) * xh) }, ctx.region.width)
}

private fun buildRow(r: List<List<WordSpec>>, isHeader: Boolean, widths: List<Float>, ctx: Ctx, xh: Float, space: Float): Row {
    val cells = List(widths.size) { c -> wrapCell(cellSpecs(r, c), ctx, xh, space, widths[c] - 2 * PAD_X * xh) }
    return Row(cells, cells.maxOf { it.lineCount } * CELL_LINE + 2 * PAD_Y, isHeader)
}

/**
 * A ruled table: the hand rules the grid first (double rule under the header, a rule under each row, rules between the
 * columns), then writes the cells row by row. Columns are sized to their content, squeezed and wrapped when the table
 * is wider than the region; a table taller than the page breaks between rows and repeats its header.
 */
internal fun layoutTable(t: Block.Table, cursor: LayoutCursor, ctx: Ctx): Pair<LaidBlock, LayoutCursor> {
    val xh = ctx.region.xHeight * CELL_SCALE
    val space = (ctx.set.glyphs[' ']?.adv ?: 0.5f).coerceIn(0.3f, 0.8f) * xh
    val header = t.header.map { runsToWords(it.map { i -> i.text().copy(bold = true) }) }
    val body = t.rows.map { r -> r.map { runsToWords(it.map { i -> i.text() }) } }
    val cols = max(header.size, body.maxOfOrNull { it.size } ?: 0)
    if (cols == 0) return LaidBlock(emptyList(), emptyList(), null) to cursor

    val available = ctx.region.width
    val widths = columnWidths(listOf(header) + body, cols, ctx, xh, space)

    val headerRow = buildRow(header, true, widths, ctx, xh, space)
    val rows = body.map { buildRow(it, false, widths, ctx, xh, space) }

    val words = ArrayList<LaidWord>(); val paths = ArrayList<LaidPath>()
    var page = cursor.page
    var lineNo = cursor.lineCount; var wordNo = cursor.wordCount
    val left = ctx.region.left + max(0f, (available - widths.sum()) / 2f)
    var top = findBox(page, cursor.lineTop, ctx.region.left, (headerRow.heightUnits + rows.firstOrNull()?.heightUnits.orZero()) * xh, ctx)
        .also { page = it.first }.second
    var tableTop = top
    var minL = left; var maxR = left + widths.sum(); var maxB = top

    fun closeSegment() { // vertical rules for the part of the table that sits on this page
        var x = left
        paths += LaidPath.line(page, x, tableTop, x, maxB)
        for (w in widths) { x += w; paths += LaidPath.line(page, x, tableTop, x, maxB) }
        paths += LaidPath.line(page, left, tableTop, left + widths.sum(), tableTop)
    }

    fun place(row: Row) {
        val h = row.heightUnits * xh
        var x = left
        for ((c, cell) in row.cells.withIndex()) {
            for ((li, line) in cell.lines.withIndex()) {
                var wx = x + PAD_X * xh
                val baseline = top + (PAD_Y + li * CELL_LINE + BASELINE_IN_LINE) * xh
                for (cw in line.words) {
                    // no line-long drift in a cell: the rules are too close
                    words += LaidWord(page, wx, baseline, cw.scale, cw.chars, cw.width, cw.spec.bold, lineNo, wordNo++)
                    wx += cw.width + space
                }
                lineNo++
            }
            x += widths[c]
        }
        top += h; maxB = top
        paths += LaidPath.line(page, left, top, left + widths.sum(), top)
        if (row.header) paths += LaidPath.line(page, left, top + DOUBLE_RULE_GAP * xh, left + widths.sum(), top + DOUBLE_RULE_GAP * xh)
        if (row.header) top += DOUBLE_RULE_GAP * xh
    }

    place(headerRow)
    for (row in rows) {
        val h = row.heightUnits * xh
        val pastBottom = top + h > ctx.region.bottom && top > ctx.region.pageTop
        val blocked = !isFree(ctx, page, PageRect(left, top, left + widths.sum(), top + h))
        if (pastBottom || blocked) { // next page, or something is already written here: carry on below it, header again
            closeSegment()
            val from = if (pastBottom) page + 1 to ctx.region.pageTop else page to top
            val (p, t) = findBox(from.first, from.second, left, headerRow.heightUnits * xh + h, ctx)
            page = p; top = t; tableTop = top; maxB = top
            place(headerRow)
        }
        place(row)
    }
    closeSegment()
    val bounds = PageRect(minL, tableTop, maxR, maxB)
    return LaidBlock(words, paths, bounds, pathsFirst = true) to
        LayoutCursor(page, maxB + ctx.region.lineHeight, lineNo, wordNo)
}

private fun Float?.orZero() = this ?: 0f

private fun isFree(ctx: Ctx, page: Int, r: PageRect) = ctx.occ.on(page)?.isFree(r) ?: true

