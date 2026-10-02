package com.nisaba.device

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.shared.PagePoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Fallback for emulators/non-Boox and for `adb input stylus` testing: draws from plain MotionEvents. */
class MotionEventPenDevice(private val acceptFinger: Boolean = false) : PenDevice {
    override var onLiveChanged: () -> Unit = {}
    private val flow = MutableSharedFlow<PenEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PenEvent> = flow
    override val liveCount: Int get() = if (erasing) 0 else n

    override fun liveSlice(from: Int, to: Int): InkPoints? {
        val a = from.coerceIn(0, n); val b = to.coerceIn(a, n)
        return if (b <= a) null else InkPoints(xs.copyOfRange(a, b), ys.copyOfRange(a, b), ps.copyOfRange(a, b), ts.copyOfRange(a, b))
    }

    private var tool = PenTool.Pen
    private var exclude: List<Rect> = emptyList()
    private var xs = FloatArray(INITIAL); private var ys = FloatArray(INITIAL)
    private var ps = FloatArray(INITIAL); private var ts = IntArray(INITIAL)
    private var n = 0
    private var t0 = 0L
    private var erasing = false

    override fun attach(view: View, drawArea: Rect, exclude: List<Rect>) { this.exclude = exclude }
    override fun setExclusions(exclude: List<Rect>) { this.exclude = exclude }
    override fun suspendRawInk() = Unit
    override fun resumeRawInk() = Unit
    override fun setTool(tool: PenTool) { this.tool = tool }
    override fun detach() { reset() }

    override fun handleTouch(e: MotionEvent): Boolean {
        val type = e.getToolType(0)
        val isPen = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER ||
            (acceptFinger && type == MotionEvent.TOOL_TYPE_FINGER)
        if (!isPen) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (exclude.any { it.contains(e.x.toInt(), e.y.toInt()) }) return false
                reset(); t0 = e.eventTime
                erasing = type == MotionEvent.TOOL_TYPE_ERASER || tool != PenTool.Pen
                flow.tryEmit(PenEvent.PenDown(e.x, e.y))
                add(e.x, e.y, pressure(e), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> if (n > 0) {
                for (h in 0 until e.historySize) {
                    add(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalPressure(h), e.getHistoricalEventTime(h))
                }
                add(e.x, e.y, pressure(e), e.eventTime)
            }
            MotionEvent.ACTION_UP -> if (n > 0) { add(e.x, e.y, pressure(e), e.eventTime); finish() }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }

    private fun pressure(e: MotionEvent) = if (e.pressure <= 0f) DEFAULT_PRESSURE else e.pressure.coerceIn(0f, 1f)

    private fun add(x: Float, y: Float, p: Float, time: Long) {
        if (n == xs.size) { xs = xs.copyOf(n * 2); ys = ys.copyOf(n * 2); ps = ps.copyOf(n * 2); ts = ts.copyOf(n * 2) }
        xs[n] = x; ys[n] = y; ps[n] = p; ts[n] = (time - t0).toInt(); n++
        if (!erasing) onLiveChanged()
    }

    private fun finish() {
        if (erasing) flow.tryEmit(PenEvent.EraseDone(List(n) { PagePoint(xs[it], ys[it]) }))
        else flow.tryEmit(PenEvent.StrokeDone(InkPoints(xs.copyOf(n), ys.copyOf(n), ps.copyOf(n), ts.copyOf(n))))
        reset()
    }

    private fun reset() { n = 0; erasing = false; onLiveChanged() }

    private companion object {
        const val INITIAL = 256
        const val DEFAULT_PRESSURE = 0.5f
    }
}
