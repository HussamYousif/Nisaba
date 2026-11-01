package com.nisaba.canvas

import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageRect
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt

// How far an endless canvas is zoomed: a few discrete levels (e-ink draws frames, not a smooth pinch).

/** The levels, about √2 apart; 1 = one page unit per screen pixel (the size you write at on a page). */
val ZOOM_LEVELS = floatArrayOf(0.25f, 0.35f, 0.5f, 0.7f, 1f, 1.4f, 2f)

/** The level nearest [z], measured as a ratio (0.6 is nearer 0.7 than 0.5). */
fun nearestZoom(z: Float): Float = ZOOM_LEVELS.minBy { abs(ln(it / z)) }

/** One level closer ([dir] > 0) or further ([dir] < 0) from [z]; stays put at either end. */
fun stepZoom(z: Float, dir: Int): Float {
    val i = ZOOM_LEVELS.indexOfFirst { it == nearestZoom(z) }
    return ZOOM_LEVELS[(i + dir.coerceIn(-1, 1)).coerceIn(0, ZOOM_LEVELS.lastIndex)]
}

/**
 * The view's new top-left (page space) when zooming from [from] to [to] around the view pixel [fx],[fy]: the spot
 * under the fingers stays under them.
 */
fun zoomOrigin(origin: PagePoint, from: Float, to: Float, fx: Float, fy: Float) =
    PagePoint(origin.x + fx / from - fx / to, origin.y + fy / from - fy / to)

/** The closest level, never past 100 %, at which all of [area] fits a [w]×[h] view (the furthest level when none does). */
fun fitZoom(area: PageRect, w: Float, h: Float): Float {
    val wanted = min(min(w / maxOf(area.width, 1f), h / maxOf(area.height, 1f)), 1f)
    return ZOOM_LEVELS.lastOrNull { it <= wanted } ?: ZOOM_LEVELS.first()
}

fun zoomLabel(z: Float) = "${(z * 100).roundToInt()}%"
