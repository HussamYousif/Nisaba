package com.nisaba.ink

import com.nisaba.notebook.model.NotebookPage
import com.nisaba.notebook.model.Stroke
import com.nisaba.notebook.model.StrokePoint
import kotlin.math.hypot
import kotlin.math.min

/**
 * Stroke-level eraser (docs/04 §5, F2.3): a stroke is removed when any point of
 * the eraser path comes within `radius` page px of it.
 */
object Eraser {

    private const val DEFAULT_RADIUS = 16f

    /** Removes intersecting strokes from the page; returns the removed ones. */
    fun erase(page: NotebookPage, path: List<StrokePoint>, radius: Float = DEFAULT_RADIUS): List<Stroke> {
        if (path.isEmpty()) return emptyList()
        val removed = ArrayList<Stroke>()
        val survivors = ArrayList<Stroke>(page.strokes.size)
        for (stroke in page.strokes) {
            if (stroke.tool == "eraser") continue
            val hit = path.any { pt -> distanceToStroke(pt, stroke) < radius }
            if (hit) removed.add(stroke) else survivors.add(stroke)
        }
        page.strokes = survivors
        return removed
    }

    private fun distanceToStroke(pt: StrokePoint, stroke: Stroke): Float {
        val pts = stroke.points
        if (pts.isEmpty()) return Float.MAX_VALUE
        if (pts.size == 1) return hypot(pt.x - pts[0].x, pt.y - pts[0].y)
        var best = Float.MAX_VALUE
        for (i in 1 until pts.size) {
            best = min(best, distanceToSegment(pt, pts[i - 1], pts[i]))
        }
        return best
    }

    private fun distanceToSegment(p: StrokePoint, a: StrokePoint, b: StrokePoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 <= 0f) return hypot(p.x - a.x, p.y - a.y)
        var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2
        t = t.coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}
