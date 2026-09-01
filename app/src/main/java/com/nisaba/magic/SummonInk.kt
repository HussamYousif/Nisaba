package com.nisaba.magic

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The summon's organic ink narrative (docs/09 §3): a thin thread of ink flows
 * from the tail of the user's last stroke to the spot where the answer will
 * write itself, gathers into a blot, and is periodically fed by a travelling
 * bead of ink. The user's writing never disappears — the ink merely reaches.
 *
 * Pure drawing (page space): the animation state lives in the ink view.
 * One-time flow + slow pulse — deliberately not a looping spinner.
 */
object SummonInk {

    private val threadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK_SOFT
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val beadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK
        style = Paint.Style.FILL
    }
    private val blotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK
        style = Paint.Style.FILL
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK_SOFT
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    /** An organic cubic from the last stroke's tail to the answer locus. */
    fun buildThread(fromX: Float, fromY: Float, toX: Float, toY: Float): Path {
        val dx = toX - fromX
        val dy = toY - fromY
        val dist = hypot(dx, dy).coerceAtLeast(1f)
        val bend = (dist * 0.16f).coerceIn(24f, 110f)
        val px = -dy / dist
        val py = dx / dist
        val path = Path()
        path.moveTo(fromX, fromY)
        path.cubicTo(
            fromX + dx * 0.32f + px * bend, fromY + dy * 0.32f + py * bend,
            toX - dx * 0.32f + px * bend, toY - dy * 0.32f + py * bend,
            toX, toY,
        )
        return path
    }

    fun threadLength(path: Path): Float = PathMeasure(path, false).length

    /** The ink thread drawn up to [progress] (0..1, from the tail end). */
    fun drawThread(canvas: Canvas, path: Path, length: Float, progress: Float) {
        if (progress <= 0f || length <= 0f) return
        val seg = Path()
        PathMeasure(path, false).getSegment(0f, length * progress, seg, true)
        if (!seg.isEmpty) canvas.drawPath(seg, threadPaint)
    }

    /** The bead of ink travelling down the thread ([progress] 0..1). */
    fun drawBead(canvas: Canvas, path: Path, length: Float, progress: Float) {
        if (progress <= 0f || progress >= 1f || length <= 0f) return
        val measure = PathMeasure(path, false)
        val pos = FloatArray(2)
        measure.getPosTan(length * progress, pos, null)
        canvas.drawCircle(pos[0], pos[1], 5f, beadPaint)
    }

    /** The gathered blot at the answer locus (organic: overlapping circles). */
    fun drawBlot(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, blotPaint)
        canvas.drawCircle(cx + cos(0.7).toFloat() * radius * 0.45f, cy + sin(0.7).toFloat() * radius * 0.45f, radius * 0.62f, blotPaint)
        canvas.drawCircle(cx + cos(2.4).toFloat() * radius * 0.5f, cy + sin(2.4).toFloat() * radius * 0.5f, radius * 0.45f, blotPaint)
    }

    /** One expanding ripple ring; [t] goes 1 → 0. */
    fun drawRipple(canvas: Canvas, cx: Float, cy: Float, radius: Float, t: Float) {
        if (t <= 0f) return
        val r = radius * (0.9f + (1f - t) * 1.1f)
        canvas.drawCircle(cx, cy, r, ripplePaint)
    }
}
