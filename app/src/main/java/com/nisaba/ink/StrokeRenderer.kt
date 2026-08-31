package com.nisaba.ink

import android.graphics.Canvas
import android.graphics.Paint
import com.nisaba.magic.MagicTheme
import com.nisaba.notebook.model.Stroke
import kotlin.math.pow

/**
 * Draws a stroke as a polyline with pressure-modulated width (docs/04 §3).
 * Round caps/joins give a nib feel; width curve is nonlinear so light touches
 * are thin and firm presses are bold.
 */
object StrokeRenderer {

    /** Width factor at pressure p for a stroke with base width `base`. */
    fun widthAt(base: Float, p: Float): Float {
        val pp = p.coerceIn(0f, 1f)
        return base * (0.30f + 0.95f * pp.pow(1.4f))
    }

    fun draw(canvas: Canvas, stroke: Stroke, paint: Paint) {
        if (stroke.tool == "eraser") return // eraser strokes are paths, not ink
        val pts = stroke.points
        if (pts.size < 2) return
        paint.color = MagicTheme.inkColor(stroke.color)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            paint.strokeWidth = widthAt(stroke.width, (a.p + b.p) / 2f)
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
    }
}
