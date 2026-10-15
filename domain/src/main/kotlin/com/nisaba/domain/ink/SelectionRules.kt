package com.nisaba.domain.ink

import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.intersects
import com.nisaba.domain.shared.union

/** Share of a stroke's points that must lie inside a lasso for the stroke to be picked. */
private const val INSIDE_SHARE = 0.6f

/** A line of handwriting: where it is and the strokes it is made of, top to bottom. */
data class InkLine(val bounds: PageRect, val strokes: List<Stroke>)

/**
 * The strokes a lasso [loop] (page units, closed implicitly) goes around: most of a stroke's points inside the loop.
 * Even-odd rule, so a loop drawn either way round works.
 */
fun strokesInLoop(strokes: List<Stroke>, loop: List<PagePoint>): List<Stroke> {
    if (loop.size < 3) return emptyList()
    val box = loopBounds(loop)
    return strokes.filter { s ->
        if (!s.bounds.intersects(box)) return@filter false
        val p = s.points
        var inside = 0
        for (i in 0 until p.size) if (insideLoop(p.xs[i], p.ys[i], loop)) inside++
        p.size > 0 && inside >= p.size * INSIDE_SHARE
    }
}

fun loopBounds(loop: List<PagePoint>) = PageRect(loop.minOf { it.x }, loop.minOf { it.y }, loop.maxOf { it.x }, loop.maxOf { it.y })

private fun insideLoop(x: Float, y: Float, loop: List<PagePoint>): Boolean {
    var inside = false
    var j = loop.lastIndex
    for (i in loop.indices) {
        val a = loop[i]; val b = loop[j]
        if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}

/** The same stroke [dx]/[dy] page units away (same id: a move is one undoable replace). */
fun Stroke.movedBy(dx: Float, dy: Float): Stroke {
    val p = points
    return copy(points = InkPoints(FloatArray(p.size) { p.xs[it] + dx }, FloatArray(p.size) { p.ys[it] + dy }, p.ps, p.ts))
}

/**
 * Handwriting grouped into lines, top to bottom: a stroke joins the line its vertical middle falls in (with a little
 * slack for descenders and accents); dots and short marks join the nearest line. Each line's strokes left to right.
 */
fun inkLines(strokes: List<Stroke>): List<InkLine> {
    if (strokes.isEmpty()) return emptyList()
    val xh = estimateXHeight(strokes)
    val bands = ArrayList<MutableList<Stroke>>()
    var bottom = Float.NEGATIVE_INFINITY
    for (s in strokes.sortedBy { mid(it) }) {
        if (bands.isEmpty() || mid(s) > bottom + xh * LINE_SLACK) { bands += mutableListOf(s); bottom = s.bounds.bottom } else {
            bands.last() += s; bottom = maxOf(bottom, s.bounds.bottom)
        }
    }
    // an i's dot or an accent sorted on its own: it belongs to the nearest real line
    val (marks, lines) = bands.partition { b -> b.size < SMALL_BAND && boundsOf(b).let { it.bottom - it.top } < xh * DOT_HEIGHT }
    if (lines.isEmpty()) return bands.map { InkLine(boundsOf(it), it.sortedBy { s -> s.bounds.left }) }
    for (m in marks) lines.minBy { kotlin.math.abs(mid(boundsOf(it)) - mid(boundsOf(m))) } += m
    return lines.map { InkLine(boundsOf(it), it.sortedBy { s -> s.bounds.left }) }
}

private fun mid(s: Stroke) = (s.bounds.top + s.bounds.bottom) / 2
private fun mid(r: PageRect) = (r.top + r.bottom) / 2
private fun boundsOf(strokes: List<Stroke>) = strokes.map { it.bounds }.reduce { a, b -> a.union(b) }

private const val SMALL_BAND = 3
private const val DOT_HEIGHT = 0.6f
private const val LINE_SLACK = 0.5f
