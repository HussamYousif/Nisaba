package com.nisaba.ink

import com.nisaba.notebook.model.Stroke
import com.nisaba.notebook.model.StrokePoint
import java.util.UUID

/**
 * Accumulates raw stylus input into a Stroke (docs/04 §5).
 * Point allocation is amortized: ArrayList grows geometrically, no per-point
 * object churn beyond the StrokePoint value.
 */
class StrokeBuilder {
    private val pts = ArrayList<StrokePoint>(64)
    private var tool = "pen"
    private var baseWidth = 3.0f
    private var started = false
    private var firstTime = 0L

    val isActive: Boolean get() = started

    fun start(x: Float, y: Float, pressure: Float, timeMs: Long, erasing: Boolean) {
        pts.clear()
        tool = if (erasing) "eraser" else "pen"
        started = true
        firstTime = timeMs
        pts.add(StrokePoint(x, y, pressure.coerceIn(0f, 1f), 0L))
    }

    fun append(x: Float, y: Float, pressure: Float, timeMs: Long) {
        if (!started) return
        pts.add(StrokePoint(x, y, pressure.coerceIn(0f, 1f), (timeMs - firstTime).coerceAtLeast(0L)))
    }

    /** Consumes the stroke: returns it and resets. Null when too short. */
    fun build(): Stroke? {
        val s = buildPreview() ?: return null
        started = false
        pts.clear()
        return s
    }

    /** Non-consuming snapshot of the in-progress stroke (for onDraw). */
    fun buildPreview(): Stroke? {
        if (!started || pts.size < 2) return null
        return Stroke(
            id = "s-preview",
            tool = tool,
            width = baseWidth,
            points = pts.toList(),
        )
    }

    fun cancel() {
        started = false
        pts.clear()
    }
}
