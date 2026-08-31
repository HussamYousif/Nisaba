package com.nisaba.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * A drawing block that inks itself in (docs/08 §5): each path is revealed
 * front-to-back via PathMeasure segments, in element order, in discrete
 * e-ink-safe steps (never smooth per-frame animation). Skippable.
 */
class DrawingInkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var items: List<SvgInkRenderer.DrawItem> = emptyList()
    private var progress = 1f // 0..1 over the whole drawing
    private var playing = false
    private val handler = Handler(Looper.getMainLooper())
    private var onDone: (() -> Unit)? = null

    private val tick = Runnable { advance() }

    /** Total duration of the reveal, split into discrete steps (docs/08 §5). */
    fun play(durationMs: Long = 3000L, onDone: () -> Unit = {}) {
        if (items.isEmpty()) {
            onDone()
            return
        }
        this.onDone = onDone
        playing = true
        progress = 0f
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, STEP_MS)
        revealSteps = (durationMs / STEP_MS).toInt().coerceIn(8, 24)
    }

    private var revealSteps = 12

    fun skipToEnd() {
        playing = false
        handler.removeCallbacks(tick)
        progress = 1f
        invalidate()
        onDone?.invoke()
        onDone = null
    }

    fun isAnimating(): Boolean = playing

    private fun advance() {
        if (!playing) return
        progress += 1f / revealSteps
        if (progress >= 1f) {
            progress = 1f
            playing = false
            invalidate()
            onDone?.invoke()
            onDone = null
        } else {
            invalidate()
            handler.postDelayed(tick, STEP_MS)
        }
    }

    fun setDrawing(items: List<SvgInkRenderer.DrawItem>) {
        this.items = items
        progress = 1f
        playing = false
        handler.removeCallbacks(tick)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w > 0) {
            // viewBox is square (1000×1000); cap the block height.
            val maxH = (MAX_HEIGHT_DP * resources.displayMetrics.density).toInt()
            val h = min(w, maxH)
            setMeasuredDimension(w, h)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        val scale = width.toFloat() / SvgInkRenderer.VIEWBOX
        canvas.save()
        canvas.scale(scale, scale)
        val n = items.size
        for ((i, item) in items.withIndex()) {
            val start = i.toFloat() / n
            val end = (i + 1).toFloat() / n
            val local = ((progress - start) / (end - start)).coerceIn(0f, 1f)
            if (item.fill) {
                if (local >= 1f) canvas.drawPath(item.path, item.paint)
            } else {
                val measure = PathMeasure(item.path, false)
                if (measure.length > 0f) {
                    val seg = Path()
                    measure.getSegment(0f, measure.length * local, seg, true)
                    if (!seg.isEmpty) canvas.drawPath(seg, item.paint)
                }
            }
        }
        canvas.restore()
    }

    companion object {
        private const val STEP_MS = 110L
        private const val MAX_HEIGHT_DP = 420
    }
}
