package com.nisaba.canvas

import com.nisaba.domain.ink.Stroke

/** How the page view brings its picture up to date with new ink. */
sealed interface Redraw {
    /** Paint only the strokes from index [from] on (they were appended). */
    data class Append(val from: Int) : Redraw

    /** Repaint everything (strokes were removed, replaced, reordered, or a page with ink just opened). */
    data object Rebuild : Redraw
}

/**
 * From [prev] to [next]: an append when every old stroke is still there, the same object, in place (the cheap path
 * for writing); a rebuild otherwise, and for a page that just opened with more than one stroke (one pass). A moved or
 * recoloured stroke keeps its id but is a new object, so it rebuilds.
 */
fun redrawOf(prev: List<Stroke>, next: List<Stroke>): Redraw {
    val appendedOnly = next.size >= prev.size && prev.indices.all { next[it] === prev[it] }
    val freshPage = prev.isEmpty() && next.size > 1
    return if (!appendedOnly || freshPage) Redraw.Rebuild else Redraw.Append(prev.size)
}
