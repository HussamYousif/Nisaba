package com.nisaba.domain.ink

import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.StrokeId
import com.nisaba.domain.shared.inflate
import com.nisaba.domain.shared.intersects
import com.nisaba.domain.shared.union
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

const val DEFAULT_X_HEIGHT = 44f
const val MIN_X_HEIGHT = 28f
const val MAX_X_HEIGHT = 60f
private const val X_HEIGHT_RATIO = 0.55f
private const val OUTLIER_FACTOR = 2.5f

private fun pointSegDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax; val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}

private fun cross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

private fun segmentsCross(a: PagePoint, b: PagePoint, c: PagePoint, d: PagePoint): Boolean {
    val d1 = cross(a.x, a.y, b.x, b.y, c.x, c.y); val d2 = cross(a.x, a.y, b.x, b.y, d.x, d.y)
    val d3 = cross(c.x, c.y, d.x, d.y, a.x, a.y); val d4 = cross(c.x, c.y, d.x, d.y, b.x, b.y)
    return d1 * d2 < 0 && d3 * d4 < 0
}

private fun segSegDist(a: PagePoint, b: PagePoint, c: PagePoint, d: PagePoint): Float {
    if (segmentsCross(a, b, c, d)) return 0f
    return minOf(
        pointSegDist(a.x, a.y, c.x, c.y, d.x, d.y), pointSegDist(b.x, b.y, c.x, c.y, d.x, d.y),
        pointSegDist(c.x, c.y, a.x, a.y, b.x, b.y), pointSegDist(d.x, d.y, a.x, a.y, b.x, b.y),
    )
}

private fun pathBounds(path: List<PagePoint>) = PageRect(
    path.minOf { it.x }, path.minOf { it.y }, path.maxOf { it.x }, path.maxOf { it.y },
)

/** Distance from point to the eraser polyline. */
private fun distToPath(x: Float, y: Float, path: List<PagePoint>): Float {
    if (path.size == 1) return hypot(x - path[0].x, y - path[0].y)
    var best = Float.MAX_VALUE
    for (i in 0 until path.size - 1) best = min(best, pointSegDist(x, y, path[i].x, path[i].y, path[i + 1].x, path[i + 1].y))
    return best
}

/** Ids of strokes the eraser [path] touches (stroke-eraser mode). */
fun hitStrokes(strokes: List<Stroke>, path: List<PagePoint>, radius: Float): List<StrokeId> {
    if (path.isEmpty()) return emptyList()
    val pb = pathBounds(path)
    return strokes.filter { s ->
        val reach = radius + s.width / 2f
        if (!pb.inflate(reach).intersects(s.bounds)) return@filter false
        val pts = s.points
        if (pts.size == 1) return@filter distToPath(pts.xs[0], pts.ys[0], path) <= reach
        (0 until pts.size - 1).any { i ->
            val a = PagePoint(pts.xs[i], pts.ys[i]); val b = PagePoint(pts.xs[i + 1], pts.ys[i + 1])
            if (path.size == 1) pointSegDist(path[0].x, path[0].y, a.x, a.y, b.x, b.y) <= reach
            else (0 until path.size - 1).any { j -> segSegDist(a, b, path[j], path[j + 1]) <= reach }
        }
    }.map { it.id }
}

/**
 * Area eraser: cuts [stroke] where the eraser passes. Returns the original stroke untouched when nothing
 * is erased, otherwise the surviving pieces (≥ 2 points each) under fresh ids.
 */
fun splitByEraser(stroke: Stroke, path: List<PagePoint>, radius: Float, newId: () -> StrokeId): List<Stroke> {
    if (path.isEmpty()) return listOf(stroke)
    val reach = radius + stroke.width / 2f
    val p = stroke.points
    val erased = BooleanArray(p.size) { distToPath(p.xs[it], p.ys[it], path) <= reach }
    if (erased.none { it }) return listOf(stroke)
    val pieces = ArrayList<Stroke>()
    var start = -1
    for (i in 0..p.size) {
        val cut = i == p.size || erased[i]
        if (!cut && start < 0) start = i
        if (cut && start >= 0) {
            if (i - start >= 2) pieces += stroke.copy(id = newId(), points = p.slice(start, i))
            start = -1
        }
    }
    return pieces
}

private fun median(v: List<Float>): Float {
    val s = v.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
}

/** x-height of the user's hand, from stroke bounding boxes (ignores tall outliers such as loops and underlines). */
fun estimateXHeight(strokes: List<Stroke>): Float {
    val heights = strokes.map { it.bounds.height }.filter { it > 0f }
    if (heights.isEmpty()) return DEFAULT_X_HEIGHT
    val m = median(heights)
    val core = heights.filter { it <= m * OUTLIER_FACTOR }
    return (median(core) * X_HEIGHT_RATIO).coerceIn(MIN_X_HEIGHT, MAX_X_HEIGHT)
}

fun utteranceBounds(strokes: List<Stroke>): PageRect? = strokes.map { it.bounds }.reduceOrNull { a, b -> a.union(b) }

fun clampTo(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
