package com.nisaba.scribe.layout

import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.layout.LayoutRegion.Companion.LINE_HEIGHT_FACTOR
import com.nisaba.scribe.layout.LayoutRegion.Companion.MARGIN
import com.nisaba.scribe.layout.LayoutRegion.Companion.MARGIN_BOTTOM

private const val MARK_GAP = 1.2f
private const val MARK_ROOM = 2.5f
private const val BASELINE_FACTOR = 1.7f
private const val BASELINE_LIFT = 0.2f
private const val GLOSS_LINES = 14
private const val MIN_GLOSS_LINES = 3
private const val MIN_GLOSS_WIDTH = 7f
private const val OPEN_PAGE = 0.6f
private const val GLOSS_PAD = 0.6f
private const val NEAR_STEPS = 6
private const val ROOM_LINES = 6

/**
 * Where a ✓/✗ for a checked [line] goes: just right of it on the line's own baseline; when the line runs to the edge,
 * in the margin left of it; failing that, under it.
 */
fun markRegion(page: PageSize, line: PageRect, xHeight: Float): LayoutRegion {
    val top = line.bottom - xHeight * (BASELINE_FACTOR + BASELINE_LIFT) // the mark's baseline sits on the line's
    val bottom = if (page.isEndless) top + xHeight * LINE_HEIGHT_FACTOR * GLOSS_LINES else page.h - MARGIN_BOTTOM
    val right = if (page.isEndless) line.right + xHeight * LINE_HEIGHT_FACTOR * GLOSS_LINES else page.w - MARGIN / 2
    val afterLine = line.right + xHeight * MARK_GAP
    return when {
        right - afterLine >= xHeight * MARK_ROOM -> LayoutRegion(page, afterLine, right, top, bottom, xHeight)
        line.left - xHeight * MARK_GAP - MARGIN / 4 >= xHeight * MARK_ROOM ->
            LayoutRegion(page, MARGIN / 4, line.left - xHeight * MARK_GAP, top, bottom, xHeight)
        else -> LayoutRegion(page, line.left, right, line.bottom + xHeight * BASELINE_LIFT, bottom, xHeight)
    }
}

/**
 * A margin note beside printed text: the free column of [printed] (what is printed on the page, not ink) beside the
 * [question], from its height down. The column the question was written in wins; else the widest one. Null when the
 * page has room to write below as usual (a free band just under the question, or no printed text alongside) or no
 * column is wide enough.
 */
fun glossRegion(page: PageSize, printed: Occupancy, question: PageRect, xHeight: Float): LayoutRegion? {
    if (page.isEndless) return null
    val line = xHeight * LINE_HEIGHT_FACTOR
    if (roomBelow(page, printed, question, line)) return null
    val top = question.top
    val bandBottom = minOf(page.h - MARGIN_BOTTOM, top + line * GLOSS_LINES)
    if (bandBottom - top < line * MIN_GLOSS_LINES) return null
    val step = printed.cell
    val cols = (page.w / step).toInt()
    val free = BooleanArray(cols) { c -> printed.isFree(PageRect(c * step + 1, top, (c + 1) * step - 1, bandBottom)) }
    val runs = ArrayList<IntRange>()
    var start = -1
    for (c in 0..cols) {
        val f = c < cols && free[c]
        if (f && start < 0) start = c
        if (!f && start >= 0) { runs += start until c; start = -1 }
    }
    if (runs.any { (it.last + 1 - it.first) * step > page.w * OPEN_PAGE }) return null // no printed text alongside
    val pad = xHeight * GLOSS_PAD
    val columns = runs.map { PageRect(it.first * step + pad, top, (it.last + 1) * step - pad, bandBottom) }
        .filter { it.right - it.left >= xHeight * MIN_GLOSS_WIDTH }
    val cx = (question.left + question.right) / 2
    val own = columns.firstOrNull { cx in it.left..it.right }
    val pick = own ?: columns.maxByOrNull { it.right - it.left } ?: return null
    val from = if (own != null) question.bottom + xHeight * GLOSS_PAD else top
    return LayoutRegion(page, pick.left, pick.right, from, page.h - MARGIN_BOTTOM, xHeight)
}

/** A full-width band of [ROOM_LINES] free lines starts within a few lines under the question: write there instead. */
private fun roomBelow(page: PageSize, printed: Occupancy, question: PageRect, line: Float): Boolean = (0..NEAR_STEPS).any { k ->
    val top = question.bottom + k * line / 2
    val bottom = top + line * ROOM_LINES
    bottom <= page.h - MARGIN_BOTTOM && printed.isFree(PageRect(MARGIN, top, page.w - MARGIN, bottom))
}
