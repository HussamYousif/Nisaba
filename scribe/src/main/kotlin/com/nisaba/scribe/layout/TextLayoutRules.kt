package com.nisaba.scribe.layout

import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.answer.MathNode
import com.nisaba.domain.answer.parseInlines
import com.nisaba.domain.answer.parseMath
import com.nisaba.domain.shared.PageRect
import com.nisaba.scribe.hand.GlyphSet
import kotlin.math.max
import kotlin.math.min

private const val BASELINE_FACTOR = 1.7f
private const val SPACE_UNITS = 0.55f
private const val HEADING_SCALE = 1.3f
private const val PARAGRAPH_GAP = 0.35f
private const val HANG_UNITS = 1.8f
private const val INDENT_UNITS = 1.6f
private const val MIN_SQUEEZE = 0.6f
private const val UNDERLINE_OVERSHOOT = 1.04f

data class TextRun(val text: String, val bold: Boolean, val math: MathNode? = null)

/** How one block sits on the page. */
private class Style(val scale: Float, val bold: Boolean, val firstX: Float, val restX: Float, val underline: Boolean = false)

fun interface OccupancyLookup {
    /** Occupancy of [page] (0 = the page the answer started on); null means empty. */
    fun on(page: Int): Occupancy?
}

internal class Ctx(val region: LayoutRegion, val occ: OccupancyLookup, val set: GlyphSet)

internal fun charScale(c: Char, set: GlyphSet) = if (c.isUpperCase()) set.capScale else 1f

internal fun measure(word: String, set: GlyphSet, scale: Float): Pair<List<PlacedChar>, Float> {
    val out = ArrayList<PlacedChar>(word.length)
    var x = 0f
    for (raw in word) {
        val c = if (set.resolve(raw) != null) raw else '?'
        val g = set.resolve(c) ?: continue
        val k = charScale(c, set)
        out += PlacedChar(c, g, x, k)
        x += g.adv * scale * k
    }
    return out to x
}

/**
 * Finds the next line box that is free of ink and inside the page, spilling to the next page when needed. An endless
 * canvas never spills (the column just goes on down); a page too short for even one line takes the line anyway.
 */
internal fun findLine(page: Int, top: Float, x0: Float, ctx: Ctx): Pair<Int, Float> {
    var p = page; var t = top
    val h = ctx.region.lineHeight
    val endless = ctx.region.pageSize.isEndless
    while (true) {
        if (!endless && t + h > ctx.region.bottom) {
            if (t <= ctx.region.pageTop) return p to t // a fresh page and still no room: spilling again would never end
            p++; t = ctx.region.pageTop; continue
        }
        val free = ctx.occ.on(p)?.isFree(PageRect(x0, t, ctx.region.right, t + h)) ?: true
        if (free) return p to t
        t += h / 2f
    }
}

/** [glued]: written right after the word before, no space between ("$x$." or "($x$)": the source has none). */
internal class WordSpec(val text: String, val bold: Boolean, val math: MathNode? = null, val glued: Boolean = false)

internal fun runsToWords(runs: List<TextRun>): List<WordSpec> {
    val out = ArrayList<WordSpec>()
    var tight = false // the run before ended without a space
    for (r in runs) {
        if (r.math != null) {
            out += WordSpec(r.text, r.bold, r.math, glued = tight && out.isNotEmpty())
            tight = true
            continue
        }
        val text = normalizeTypography(r.text)
        val words = text.split(' ', '\n', '\t').filter { it.isNotEmpty() }
        val startsTight = text.firstOrNull()?.isWhitespace() == false
        words.forEachIndexed { i, w -> out += WordSpec(w, r.bold, glued = i == 0 && startsTight && tight && out.isNotEmpty()) }
        tight = when {
            words.isEmpty() -> tight && text.isEmpty()
            else -> !text.last().isWhitespace()
        }
    }
    return out
}

/** Words that could not be squeezed onto a line even at [MIN_SQUEEZE] are broken into line-sized pieces. */
internal fun breakOverlong(words: List<WordSpec>, set: GlyphSet, xh: Float, maxWidth: Float): List<WordSpec> =
    words.flatMap { w ->
        val (text, bold) = w.text to w.bold
        if (w.math != null || measure(text, set, xh).second * MIN_SQUEEZE <= maxWidth) return@flatMap listOf(w)
        val pieces = ArrayList<WordSpec>()
        var start = 0
        while (start < text.length) {
            var end = start + 1
            while (end < text.length && measure(text.substring(start, end + 1), set, xh).second <= maxWidth) end++
            pieces += WordSpec(text.substring(start, end), bold, glued = start == 0 && w.glued)
            start = end
        }
        pieces
    }

/** Greedy word wrap of [runs]; returns the laid words, ruled lines and the cursor after the block. */
private fun flow(runs: List<TextRun>, style: Style, start: LayoutCursor, ctx: Ctx): Pair<LaidBlock, LayoutCursor> {
    val words = ArrayList<LaidWord>(); val rules = ArrayList<LaidPath>()
    val xh = ctx.region.xHeight * style.scale
    val space = (ctx.set.glyphs[' ']?.adv ?: SPACE_UNITS).coerceIn(0.3f, 0.8f) * xh
    var (page, top) = findLine(start.page, start.lineTop, ctx.region.left + style.firstX, ctx)
    var line = start.lineCount; var index = start.wordCount
    var x = ctx.region.left + style.firstX
    var lineStartX = x; var lineWidthUsed = 0f
    var minL = Float.MAX_VALUE; var maxR = 0f; var minT = Float.MAX_VALUE; var maxB = 0f

    fun newLine() {
        val next = findLine(page, top + ctx.region.lineHeight, ctx.region.left + style.restX, ctx)
        page = next.first; top = next.second; line++
        x = ctx.region.left + style.restX; lineStartX = x; lineWidthUsed = 0f
    }

    for (spec in breakOverlong(runsToWords(runs), ctx.set, xh, ctx.region.width - style.restX)) {
        val (text, bold) = spec.text to spec.bold
        var (chars, w) = if (spec.math != null) inlineMath(spec.math, ctx.set, xh) else measure(text, ctx.set, xh)
        if (spec.glued && x > lineStartX) x -= space // no space between them
        val avail = ctx.region.right - x
        if (x > lineStartX && w > avail) newLine()
        var scale = xh
        val maxW = ctx.region.right - x
        if (w > maxW) { // a single overlong word: squeeze it onto the line
            val k = (maxW / w).coerceAtLeast(MIN_SQUEEZE); scale = xh * k; w *= k
        }
        val baseline = top + xh / style.scale * BASELINE_FACTOR
        words += LaidWord(page, x, baseline, scale, chars, w, bold || style.bold, line, index++, lineStartX)
        minL = minOf(minL, x); maxR = maxOf(maxR, x + w)
        minT = minOf(minT, top); maxB = maxOf(maxB, top + ctx.region.lineHeight)
        x += w + space; lineWidthUsed = x - lineStartX
    }
    if (words.isEmpty()) return LaidBlock(emptyList(), emptyList(), null) to start
    if (style.underline) {
        val last = words.last()
        val x0 = ctx.region.left + style.firstX
        val y = last.baseline + xh * 0.3f
        rules += LaidPath.line(last.page, x0, y, x0 + lineWidthUsed * UNDERLINE_OVERSHOOT, y)
    }
    val bounds = PageRect(minL, minT, maxR, maxB)
    val next = LayoutCursor(page, top + ctx.region.lineHeight + ctx.region.lineHeight * PARAGRAPH_GAP, line + 1, index)
    return LaidBlock(words, rules, bounds) to next
}

internal fun Inline.text() = when (this) {
    is Inline.Text -> TextRun(s, bold)
    is Inline.Math -> TextRun(latex, false, parseMath(latex))
}

internal fun List<Inline>.runs() = map { it.text() }

/**
 * Lays [block] out from [cursor]. Text-like blocks are fully handled; blocks that need dedicated layout (tables, math,
 * diagrams — M4) degrade to readable text so an answer is never lost.
 */
fun layoutBlock(
    block: Block,
    cursor: LayoutCursor,
    region: LayoutRegion,
    occ: OccupancyLookup,
    set: GlyphSet,
): Pair<LaidBlock, LayoutCursor> {
    val ctx = Ctx(region, occ, set)
    val xh = region.xHeight
    return when (block) {
        is Block.Paragraph -> flow(block.inlines.runs(), Style(1f, false, 0f, 0f), cursor, ctx)
        is Block.Heading -> flow(block.inlines.runs(), Style(HEADING_SCALE, true, 0f, 0f, underline = block.level == 1), cursor, ctx)
        is Block.ListItem -> listItem(block, cursor, ctx, xh)
        is Block.MathBlock -> displayMath(block.latex, cursor, ctx)
        is Block.Table -> layoutTable(block, cursor, ctx)
        is Block.Diagram -> layoutDiagram(block, cursor, ctx)
        is Block.Code -> layoutCode(block, cursor, ctx)
        Block.Rule -> rule(cursor, ctx)
        is Block.Mark -> flow(markInlines(block).runs(), Style(1f, false, 0f, 0f), cursor, ctx)
    }
}

/** What a mark beside checked work says: a bold tick, or a bold cross and the correction (math and all). */
fun markInlines(m: Block.Mark): List<Inline> =
    if (m.ok || m.note.isBlank()) listOf(Inline.Text(if (m.ok) "✓" else "✗", bold = true))
    else listOf(Inline.Text("✗ ", bold = true)) + parseInlines(m.note)

private fun listItem(b: Block.ListItem, cursor: LayoutCursor, ctx: Ctx, xh: Float): Pair<LaidBlock, LayoutCursor> {
    val base = b.depth * xh * INDENT_UNITS
    val marker = if (b.ordered) b.marker else "-"
    val style = Style(1f, false, base, base + xh * HANG_UNITS)
    return flow(listOf(TextRun("$marker ", false)) + b.inlines.runs(), style, cursor, ctx) // the space: "1. Why", never "1.Why"
}

private fun rule(cursor: LayoutCursor, ctx: Ctx): Pair<LaidBlock, LayoutCursor> {
    val (page, top) = findLine(cursor.page, cursor.lineTop, ctx.region.left, ctx)
    val y = top + ctx.region.lineHeight / 2f
    val r = ctx.region
    val laid = LaidBlock(emptyList(), listOf(LaidPath.line(page, r.left, y, r.right, y)), PageRect(r.left, y, r.right, y))
    return laid to cursor.copy(page = page, lineTop = top + ctx.region.lineHeight)
}

private const val INLINE_MAX_ASCENT = 1.9f
private const val INLINE_MAX_DESCENT = 0.9f

/** An inline formula as ONE unbreakable "word" whose single glyph is the whole formula (fitted to the line height). */
internal fun inlineMath(node: MathNode, set: GlyphSet, xh: Float): Pair<List<PlacedChar>, Float> {
    var box = layoutMath(node, set, MathStyle.Text)
    val fit = min(1f, min(INLINE_MAX_ASCENT / max(box.ascent, 1e-3f), INLINE_MAX_DESCENT / max(-box.descent, 1e-3f)))
    if (fit < 1f) box = box.scaled(max(fit, MIN_SQUEEZE))
    return listOf(PlacedChar('M', box.toGlyph(), 0f, 1f)) to box.width * xh
}

private const val DISPLAY_PAD = 0.75f

/** A display formula: centred on its own line(s), as large as fits the width, taking as much height as it needs. */
private fun displayMath(latex: String, cursor: LayoutCursor, ctx: Ctx): Pair<LaidBlock, LayoutCursor> {
    val xh = ctx.region.xHeight
    var box = layoutMath(parseMath(latex), ctx.set, MathStyle.Display)
    if (box.width <= 0f) return LaidBlock(emptyList(), emptyList(), null) to cursor
    val widthLimit = ctx.region.width / xh
    if (box.width > widthLimit) box = box.scaled(widthLimit / box.width)
    val height = (box.ascent - box.descent + 2 * DISPLAY_PAD) * xh
    val (page, top) = findBox(cursor.page, cursor.lineTop, ctx.region.left, height, ctx)
    val x = ctx.region.left + (ctx.region.width - box.width * xh) / 2f
    val baseline = top + (DISPLAY_PAD + box.ascent) * xh
    val glyphs = listOf(PlacedChar('M', box.toGlyph(), 0f, 1f))
    val word = LaidWord(page, x, baseline, xh, glyphs, box.width * xh, false, cursor.lineCount, cursor.wordCount)
    val bounds = PageRect(x, top, x + box.width * xh, top + height)
    return LaidBlock(listOf(word), emptyList(), bounds) to
        LayoutCursor(page, top + height + ctx.region.lineHeight * PARAGRAPH_GAP, cursor.lineCount + 1, cursor.wordCount + 1)
}

/** Like [findLine] for an element of arbitrary [height] (a formula, a table row, a diagram). */
internal fun findBox(page: Int, top: Float, x0: Float, height: Float, ctx: Ctx): Pair<Int, Float> {
    var p = page; var t = top
    while (true) {
        if (t + height > ctx.region.bottom && t > ctx.region.pageTop) { p++; t = ctx.region.pageTop; continue }
        val free = ctx.occ.on(p)?.isFree(PageRect(x0, t, ctx.region.right, t + height)) ?: true
        if (free || t + height > ctx.region.bottom) return p to t
        t += ctx.region.lineHeight / 2f
    }
}
