package com.nisaba.magic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The "ink stirring" indicator (docs/09 §3): a small inkwell pool whose ink
 * visibly comes alive while the summon is in flight — droplets spiral in from
 * around the pool, a parchment arm swirls through it, and a ripple pulses.
 * Discrete e-ink-safe steps, small refresh region, self-contained.
 *
 * On [settle] (the answer arrived) the arm completes and the view freezes in
 * the full-stir pose; the caller hides it as the answer ink draws itself in.
 */
class InkStirView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val handler = Handler(Looper.getMainLooper())
    private var stirring = false
    private var step = 0
    private var spiralPath: Path? = null
    private var spiralLength = 0f

    private val poolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK
        style = Paint.Style.FILL
    }
    private val armPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.PARCHMENT
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK
        style = Paint.Style.FILL
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }

    private val tick = Runnable { advance() }

    /** Starts the stirring loop. */
    fun stir() {
        if (stirring) return
        stirring = true
        step = 0
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, STEP_MS)
        invalidate()
    }

    val isStirring: Boolean get() = stirring

    /** The answer arrived: finish the pose and stop looping. */
    fun settle() {
        stirring = false
        handler.removeCallbacks(tick)
        step = CYCLE_STEPS
        invalidate()
    }

    fun stop() {
        stirring = false
        handler.removeCallbacks(tick)
    }

    private fun advance() {
        step++
        if (step > CYCLE_STEPS) step = 0
        invalidate()
        if (stirring) handler.postDelayed(tick, STEP_MS)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildSpiral()
    }

    private fun rebuildSpiral() {
        val r = poolRadius()
        if (r <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val path = Path()
        val turns = 2.2f
        val steps = 60
        for (i in 0..steps) {
            val a = Math.toRadians((i.toFloat() / steps * turns * 360f).toDouble())
            val rad = r * 0.82f * (i.toFloat() / steps)
            val x = (cx + cos(a).toFloat() * rad).toFloat()
            val y = (cy + sin(a).toFloat() * rad).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        spiralPath = path
        spiralLength = PathMeasure(path, false).length
    }

    override fun onDraw(canvas: Canvas) {
        if (!stirring && step == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val r = poolRadius()
        if (r <= 0f) return
        val t = step.toFloat() / CYCLE_STEPS

        // The pool.
        canvas.drawCircle(cx, cy, r, poolPaint)

        // The stirring arm: a parchment spiral that draws itself inward.
        val arm = ((t - 0.2f) / 0.55f).coerceIn(0f, 1f)
        val spiral = spiralPath
        if (spiral != null && spiralLength > 0f && arm > 0f) {
            val seg = Path()
            PathMeasure(spiral, false).getSegment(0f, spiralLength * arm, seg, true)
            if (!seg.isEmpty) canvas.drawPath(seg, armPaint)
        }

        // Droplets spiralling into the pool (they merge at the rim).
        for (i in 0 until 4) {
            val angle = Math.toRadians((i * 90 + t * 400f).toDouble())
            val rad = (1.45f - 0.95f * t.coerceAtMost(0.9f)) * r
            if (rad > r * 0.92f) {
                val x = cx + cos(angle).toFloat() * rad
                val y = cy + sin(angle).toFloat() * rad
                canvas.drawCircle(x, y, dp(3.2f), dropPaint)
            }
        }

        // Ripple pulse at the end of each cycle.
        if (t > 0.82f) {
            val rippleR = r + (t - 0.82f) / 0.18f * dp(9f)
            canvas.drawCircle(cx, cy, rippleR, ripplePaint)
        }
    }

    private fun poolRadius(): Float = min(width, height) / 2f * 0.78f

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        private const val STEP_MS = 380L
        private const val CYCLE_STEPS = 12
    }
}
