package com.nisaba.scribe.hand

private const val EXIT_MAX_Y = 0.3f       // the pen leaves the letter near the baseline
private const val EXIT_MIN_X_FRACTION = 0.6f // ...on its right-hand side
private const val ENTRY_MAX_X = 0.6f      // the next letter starts on its left...
private const val ENTRY_MIN_Y = 0.45f     // ...at least half way up
private const val MAX_LINK_GAP = 1.2f     // never bridge more than this many x-heights

/** Where the pen leaves and enters a letter (glyph units, y up), taken from its first and last regular strokes. */
class JoinPoints(val exitX: Float, val exitY: Float, val entryX: Float, val entryY: Float)

fun joinPointsOf(g: Glyph): JoinPoints? {
    val regular = g.strokes.filter { !it.delayed && it.size > 0 }
    val first = regular.firstOrNull() ?: return null
    val last = regular.last()
    return JoinPoints(last.pts[last.pts.size - 2], last.pts[last.pts.size - 1], first.pts[0], first.pts[1])
}

private fun Char.isPlainLower() = this in 'a'..'z'

/**
 * True when [prev] flows into [next] without the pen having to loop backwards: prev exits low on its right, next
 * starts on its left. This turns print letterforms into a joined, semi-cursive hand while staying legible.
 */
fun canJoin(prev: Char, prevGlyph: Glyph, next: Char, nextGlyph: Glyph, gapUnits: Float): Boolean {
    if (!prev.isPlainLower() || !next.isPlainLower() || gapUnits > MAX_LINK_GAP) return false
    val a = joinPointsOf(prevGlyph) ?: return false
    val b = joinPointsOf(nextGlyph) ?: return false
    return a.exitY <= EXIT_MAX_Y && a.exitX >= prevGlyph.adv * EXIT_MIN_X_FRACTION && b.entryX <= ENTRY_MAX_X && b.entryY >= ENTRY_MIN_Y
}

/**
 * Points (excluding both ends) of a soft link from the exit at ([x0],[y0]) to the entry at ([x1],[y1]): the pen keeps
 * going along the baseline, then rises into the next letter.
 */
fun linkCurve(x0: Float, y0: Float, x1: Float, y1: Float): List<Pair<Float, Float>> {
    val cx = x0 + (x1 - x0) * 0.65f
    val cy = y0
    return listOf(0.3f, 0.6f, 0.85f).map { t ->
        val u = 1f - t
        (u * u * x0 + 2f * u * t * cx + t * t * x1) to (u * u * y0 + 2f * u * t * cy + t * t * y1)
    }
}
