package com.nisaba.magic

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import kotlin.math.cos
import kotlin.math.sin

/**
 * The ink-stirring locus, drawn ON the page (docs/09 §3): a pool of ink that
 * gathers and stirs at the exact spot where the answer will write itself.
 * Droplets spiral in, a parchment arm swirls through the pool, a ripple
 * pulses. The user's strokes are never touched.
 *
 * Pure drawing: the animation state lives in the ink view; this object only
 * renders a given step (discrete, e-ink-safe). Sizes are page-space px.
 */
object InkStir {

    private val poolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK
        style = Paint.Style.FILL
    }
    private val armPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.PARCHMENT
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK
        style = Paint.Style.FILL
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private var spiralCache: Path? = null
    private var spiralLength = 0f
    private var spiralRadius = -1f

    /** Draws the stirring pool centred at [cx],[cy] for [step]/[cycleSteps]. */
    fun draw(canvas: Canvas, cx: Float, cy: Float, radius: Float, step: Int, cycleSteps: Int) {
        if (step <= 0) return
        val t = step.toFloat() / cycleSteps

        canvas.save()
        canvas.translate(cx, cy)

        canvas.drawCircle(0f, 0f, radius, poolPaint)

        // The stirring arm: a parchment spiral drawing itself inward.
        val arm = ((t - 0.2f) / 0.55f).coerceIn(0f, 1f)
        if (arm > 0f) {
            val spiral = spiral(radius)
            val seg = Path()
            PathMeasure(spiral, false).getSegment(0f, spiralLength * arm, seg, true)
            if (!seg.isEmpty) canvas.drawPath(seg, armPaint)
        }

        // Droplets spiralling into the pool, merging at the rim.
        for (i in 0 until 4) {
            val angle = Math.toRadians((i * 90 + t * 400f).toDouble())
            val rad = (1.45f - 0.95f * t.coerceAtMost(0.9f)) * radius
            if (rad > radius * 0.92f) {
                canvas.drawCircle(cos(angle).toFloat() * rad, sin(angle).toFloat() * rad, 6f, dropPaint)
            }
        }

        // Ripple pulse at the end of each cycle.
        if (t > 0.82f) {
            val rippleR = radius + (t - 0.82f) / 0.18f * 16f
            canvas.drawCircle(0f, 0f, rippleR, ripplePaint)
        }

        canvas.restore()
    }

    private fun spiral(radius: Float): Path {
        spiralCache?.let { if (spiralRadius == radius) return it }
        val path = Path()
        val turns = 2.2f
        val steps = 60
        for (i in 0..steps) {
            val a = Math.toRadians((i.toFloat() / steps * turns * 360f).toDouble())
            val rad = radius * 0.82f * (i.toFloat() / steps)
            val x = cos(a).toFloat() * rad
            val y = sin(a).toFloat() * rad
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        spiralCache = path
        spiralLength = PathMeasure(path, false).length
        spiralRadius = radius
        return path
    }
}
