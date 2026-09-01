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

    /** Switches an in-progress pen stroke to eraser (pen button pressed mid-stroke). */
    fun setErasing() {
        if (started && tool == "pen") tool = "eraser"
    }

    /** Consumes the stroke: returns it and resets. Null when too short. */
    fun build(): Stroke? {
        val s = buildPreview() ?: return null
        started = false
        pts.clear()
        // buildPreview() is shared with onDraw, where the id is irrelevant;
        // a committed stroke needs a unique id — undo/redo and erasing match
        // strokes by id, and "s-preview" for every stroke made undo drop the
        // wrong one (audit P0-1).
        return s.copy(id = "s-" + UUID.randomUUID().toString().take(8))
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
