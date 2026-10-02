package com.nisaba.ui.brand

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * The "thinking" indicator: a drop of ink at the spot where the answer will start, swelling and rippling.
 * Six discrete frames (looped at ~3 fps) — never a smooth animation, which e-ink would smear.
 */
class InkwellRenderer(private val colorArgb: Int = LAPIS) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb; style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb; style = Paint.Style.STROKE; strokeWidth = RING_WIDTH }

    /** Bounding radius of everything the renderer may draw, for dirty-rect invalidation. */
    val reach: Float get() = MAX_REACH

    fun draw(canvas: Canvas, cx: Float, cy: Float, frame: Int) {
        val f = ((frame % FRAMES) + FRAMES) % FRAMES
        when (f) {
            0 -> drop(canvas, cx, cy, 6f)
            1 -> drop(canvas, cx, cy, 11f)
            2 -> { drop(canvas, cx, cy, 14f); canvas.drawCircle(cx, cy, 24f, ring) }
            3 -> { drop(canvas, cx, cy, 14f); canvas.drawCircle(cx, cy, 24f, ring); canvas.drawCircle(cx, cy, 36f, ring) }
            4 -> { drop(canvas, cx, cy, 13f); canvas.drawCircle(cx, cy, 36f, ring); canvas.drawCircle(cx, cy, 48f, ring) }
            else -> drop(canvas, cx, cy, 10f)
        }
    }

    /** A teardrop: round belly with a short tip pointing up. */
    private fun drop(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Path().apply {
            moveTo(cx, cy - r * 1.9f)
            quadTo(cx + r * 0.9f, cy - r * 0.6f, cx + r, cy)
            arcTo(cx - r, cy - r, cx + r, cy + r, 0f, 180f, false)
            quadTo(cx - r * 0.9f, cy - r * 0.6f, cx, cy - r * 1.9f)
            close()
        }
        canvas.drawPath(p, fill)
    }

    companion object {
        const val FRAMES = 6
        const val FRAME_MILLIS = 333L
        const val LAPIS = 0xFF27407A.toInt()
        private const val RING_WIDTH = 3f
        private const val MAX_REACH = 54f
    }
}
