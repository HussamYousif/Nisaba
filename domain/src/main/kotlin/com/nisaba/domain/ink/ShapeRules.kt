package com.nisaba.domain.ink

import com.nisaba.domain.shared.PagePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What the shape tool draws. [Auto] = draw freely; a rough line, triangle, rectangle or ellipse is cleaned up. */
enum class ShapeKind { Auto, Line, Arrow, Rectangle, Ellipse, Triangle }

private const val STEP = 8f                // points along a clean edge, page units
private const val PRESSURE = 0.55f
private const val POINT_MS = 6
private const val ELLIPSE_POINTS = 72
private const val ARROW_HEAD = 0.22f       // of the arrow's length…
private const val ARROW_HEAD_MAX = 60f     // …but never longer than this
private const val ARROW_ANGLE = 0.45       // radians either side of the shaft
private const val AXIS_SNAP_DEG = 8.0      // nearly level or upright lines are made exactly so

private const val LINE_STRAIGHTNESS = 0.92f // chord / path length above this = a line
private const val MIN_SIZE = 40f            // smaller scribbles are left alone
private const val CLOSED_GAP = 0.22f        // ends this close (of the diagonal) = a closed shape
private const val CORNER_EPS = 0.07f        // simplification tolerance, of the diagonal
private const val ELLIPSE_SPREAD = 0.16f    // max deviation of the normalised radius for an ellipse

/** A pen stroke along [corners] (in order), evenly densified, steady pressure, so it renders like a ruled line. */
private fun polyline(corners: List<PagePoint>): InkPoints {
    val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
    for (i in 0 until corners.size - 1) {
        val a = corners[i]; val b = corners[i + 1]
        val n = max(1, (hypot(b.x - a.x, b.y - a.y) / STEP).toInt())
        for (k in 0 until n) { val t = k / n.toFloat(); xs += a.x + (b.x - a.x) * t; ys += a.y + (b.y - a.y) * t }
    }
    corners.lastOrNull()?.let { xs += it.x; ys += it.y }
    return InkPoints(xs.toFloatArray(), ys.toFloatArray(), FloatArray(xs.size) { PRESSURE }, IntArray(xs.size) { it * POINT_MS })
}

private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): InkPoints {
    val start = PI * 0.75 // from the upper left, as a hand would
    return polyline(
        List(ELLIPSE_POINTS + 1) { i ->
            val a = start + 2 * PI * i / ELLIPSE_POINTS
            PagePoint(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat())
        },
    )
}

/** A line from [a] to [b], made exactly level or upright when it nearly is. */
private fun snapLine(a: PagePoint, b: PagePoint): PagePoint {
    val deg = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()))
    val off = ((deg % 90) + 90) % 90
    return when {
        off < AXIS_SNAP_DEG || off > 90 - AXIS_SNAP_DEG ->
            if (abs(b.x - a.x) >= abs(b.y - a.y)) PagePoint(b.x, a.y) else PagePoint(a.x, b.y)
        else -> b
    }
}

/** The clean shape of [kind] spanning a drag from [from] to [to] (the drag's box; a line or arrow runs along it). */
fun shapeStroke(kind: ShapeKind, from: PagePoint, to: PagePoint): InkPoints {
    val l = min(from.x, to.x); val r = max(from.x, to.x); val t = min(from.y, to.y); val b = max(from.y, to.y)
    return when (kind) {
        ShapeKind.Auto, ShapeKind.Line -> polyline(listOf(from, snapLine(from, to)))
        ShapeKind.Arrow -> {
            val tip = snapLine(from, to)
            val ang = atan2((tip.y - from.y).toDouble(), (tip.x - from.x).toDouble())
            val head = min(ARROW_HEAD_MAX, hypot(tip.x - from.x, tip.y - from.y) * ARROW_HEAD)
            fun wing(side: Int) = PagePoint(
                tip.x - head * cos(ang + side * ARROW_ANGLE).toFloat(), tip.y - head * sin(ang + side * ARROW_ANGLE).toFloat(),
            )
            polyline(listOf(from, tip, wing(1), tip, wing(-1)))
        }
        ShapeKind.Rectangle -> polyline(listOf(PagePoint(l, t), PagePoint(r, t), PagePoint(r, b), PagePoint(l, b), PagePoint(l, t)))
        ShapeKind.Ellipse -> ellipse((l + r) / 2, (t + b) / 2, (r - l) / 2, (b - t) / 2)
        ShapeKind.Triangle -> polyline(listOf(PagePoint((l + r) / 2, t), PagePoint(r, b), PagePoint(l, b), PagePoint((l + r) / 2, t)))
    }
}

/** Ramer–Douglas–Peucker: the corners of [p] within tolerance [eps]. */
private fun simplify(p: List<PagePoint>, eps: Float): List<PagePoint> {
    if (p.size < 3) return p
    val a = p.first(); val b = p.last()
    var far = -1f; var at = 0
    for (i in 1 until p.size - 1) {
        val d = distToSegment(p[i], a, b)
        if (d > far) { far = d; at = i }
    }
    if (far <= eps) return listOf(a, b)
    return simplify(p.subList(0, at + 1), eps).dropLast(1) + simplify(p.subList(at, p.size), eps)
}

private fun distToSegment(p: PagePoint, a: PagePoint, b: PagePoint): Float {
    val dx = b.x - a.x; val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    if (len2 == 0f) return hypot(p.x - a.x, p.y - a.y)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

/**
 * The clean version of a freehand stroke when it is clearly a line, triangle, rectangle (or other quadrilateral) or
 * ellipse; null when it is anything else (writing, a squiggle), which then stays as drawn.
 */
fun recognizeShape(points: InkPoints): InkPoints? {
    val n = points.size
    if (n < 5) return null
    val pts = List(n) { PagePoint(points.xs[it], points.ys[it]) }
    val l = points.xs.min(); val r = points.xs.max(); val t = points.ys.min(); val b = points.ys.max()
    val diag = hypot(r - l, b - t)
    if (diag < MIN_SIZE) return null
    var length = 0f
    for (i in 1 until n) length += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
    val first = pts.first(); val last = pts.last()
    val chord = hypot(last.x - first.x, last.y - first.y)
    if (chord / length > LINE_STRAIGHTNESS) return polyline(listOf(first, snapLine(first, last)))
    if (chord > diag * CLOSED_GAP) return null
    val corners = simplify(pts, diag * CORNER_EPS).dropLast(1) // closed: the last point is the first again
    return when (corners.size) {
        3 -> polyline(corners + corners.first())
        4 -> if (isBoxLike(corners, l, r, t, b, diag)) {
            shapeStroke(ShapeKind.Rectangle, PagePoint(l, t), PagePoint(r, b))
        } else {
            polyline(corners + corners.first())
        }
        else -> if (isEllipse(pts, l, r, t, b)) shapeStroke(ShapeKind.Ellipse, PagePoint(l, t), PagePoint(r, b)) else null
    }
}

/** Four corners each near a different corner of the bounding box: a rectangle drawn by hand. */
private fun isBoxLike(c: List<PagePoint>, l: Float, r: Float, t: Float, b: Float, diag: Float): Boolean {
    val box = listOf(PagePoint(l, t), PagePoint(r, t), PagePoint(r, b), PagePoint(l, b))
    return box.all { k -> c.any { hypot(it.x - k.x, it.y - k.y) < diag * CLOSED_GAP } }
}

private fun isEllipse(p: List<PagePoint>, l: Float, r: Float, t: Float, b: Float): Boolean {
    val cx = (l + r) / 2; val cy = (t + b) / 2; val rx = (r - l) / 2; val ry = (b - t) / 2
    if (rx <= 0f || ry <= 0f) return false
    val radii = p.map { hypot((it.x - cx) / rx, (it.y - cy) / ry) }
    val mean = radii.average().toFloat()
    val spread = sqrt(radii.sumOf { ((it - mean) * (it - mean)).toDouble() } / radii.size).toFloat()
    return spread < ELLIPSE_SPREAD
}
