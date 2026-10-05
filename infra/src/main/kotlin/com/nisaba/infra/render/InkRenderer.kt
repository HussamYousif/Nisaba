package com.nisaba.infra.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.shared.StrokeId
import com.nisaba.scribe.hand.PenProfile
import com.nisaba.scribe.hand.strokeOutline

/** Draws strokes as filled variable-width outlines. Outline paths are cached per stroke. */
class InkRenderer(private val profile: PenProfile = PenProfile.DEFAULT) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cache = object : LruCache<StrokeId, Path>(MAX_CACHED_POINTS) {
        override fun sizeOf(key: StrokeId, value: Path) = 1
    }

    fun outline(stroke: Stroke): Path = cache[stroke.id] ?: buildPath(stroke).also { cache.put(stroke.id, it) }

    fun drawStroke(canvas: Canvas, stroke: Stroke) {
        paint.color = stroke.color.argb
        canvas.drawPath(outline(stroke), paint)
    }

    fun drawAll(canvas: Canvas, strokes: List<Stroke>) = strokes.forEach { drawStroke(canvas, it) }

    /** Live (uncommitted) stroke drawn straight from points, colour [argb]. */
    fun drawLive(canvas: Canvas, points: com.nisaba.domain.ink.InkPoints, argb: Int, widthScale: Float = 1f) {
        if (points.size == 0) return
        paint.color = argb
        canvas.drawPath(pathOf(strokeOutline(points, profile, widthScale)), paint)
    }

    private fun buildPath(stroke: Stroke) = pathOf(strokeOutline(stroke.points, profile, stroke.width / DEFAULT_WIDTH))

    private fun pathOf(poly: FloatArray) = Path().apply {
        if (poly.size < 4) return@apply
        moveTo(poly[0], poly[1])
        var i = 2
        while (i < poly.size) { lineTo(poly[i], poly[i + 1]); i += 2 }
        close()
    }

    private companion object {
        const val MAX_CACHED_POINTS = 4000
        const val DEFAULT_WIDTH = 3.5f
    }
}
