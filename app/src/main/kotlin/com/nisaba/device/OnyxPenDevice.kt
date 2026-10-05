package com.nisaba.device

import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.shared.PagePoint
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Low-latency stylus input through the Onyx pen SDK (TouchHelper raw drawing).
 * The SDK draws the live ink itself; we receive the finished stroke and commit it to our own layer.
 *
 * Pressure scale and side-button/eraser behaviour are provisional until spike S1 is completed with a real pen.
 */
class OnyxPenDevice : PenDevice {
    private val flow = MutableSharedFlow<PenEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PenEvent> = flow
    override val liveCount: Int = 0
    override fun liveSlice(from: Int, to: Int): InkPoints? = null
    override var onLiveChanged: () -> Unit = {}

    private var helper: TouchHelper? = null
    private var suspendCount = 0
    private var maxPressure = DEFAULT_MAX_PRESSURE
    private var penArgb = android.graphics.Color.BLACK
    private var penWidth = STROKE_WIDTH
    private var tool = PenTool.Pen

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, p: TouchPoint) { flow.tryEmit(PenEvent.PenDown(p.x, p.y)) }
        override fun onEndRawDrawing(b: Boolean, p: TouchPoint) = Unit
        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) = Unit
        override fun onRawDrawingTouchPointListReceived(l: TouchPointList) {
            if (l.size() > 0) flow.tryEmit(PenEvent.StrokeDone(toInkPoints(l)))
        }
        override fun onBeginRawErasing(b: Boolean, p: TouchPoint) { flow.tryEmit(PenEvent.PenDown(p.x, p.y)) }
        override fun onEndRawErasing(b: Boolean, p: TouchPoint) = Unit
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) = Unit
        override fun onRawErasingTouchPointListReceived(l: TouchPointList) {
            if (l.size() > 0) flow.tryEmit(PenEvent.EraseDone(List(l.size()) { PagePoint(l.get(it).x, l.get(it).y) }))
        }
        override fun onPenUpRefresh(r: RectF) = Unit
    }

    private fun toInkPoints(l: TouchPointList): InkPoints {
        val n = l.size(); val t0 = l.get(0).timestamp
        // Pressure arrives in device units; normalise (scale verified in S1; clamp keeps it safe meanwhile).
        val scale = if (l.get(0).pressure > 1f || (0 until n).any { l.get(it).pressure > 1f }) maxPressure else 1f
        return InkPoints(
            FloatArray(n) { l.get(it).x }, FloatArray(n) { l.get(it).y },
            FloatArray(n) { (l.get(it).pressure / scale).coerceIn(0f, 1f) }, IntArray(n) { (l.get(it).timestamp - t0).toInt() },
        )
    }

    override fun attach(view: View, drawArea: Rect, exclude: List<Rect>) {
        detach()
        runCatching {
            helper = TouchHelper.create(view, callback).apply {
                setStrokeWidth(penWidth).setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
                setLimitRect(drawArea, exclude).openRawDrawing()
                setStrokeColor(penArgb)
                setRawDrawingRenderEnabled(!tool.erases)
                enableSideBtnErase(true)
                setRawDrawingEnabled(suspendCount == 0)
            }
        }.onFailure { Log.e(TAG, "attach failed", it) }
    }

    override fun setExclusions(exclude: List<Rect>) {
        val h = helper ?: return
        val v = h.hostView ?: return
        runCatching { h.setLimitRect(Rect(0, 0, v.width, v.height), exclude) }
    }

    override fun suspendRawInk() { if (suspendCount++ == 0) helper?.setRawDrawingEnabled(false) }
    override fun resumeRawInk() { if (suspendCount > 0 && --suspendCount == 0) helper?.setRawDrawingEnabled(true) }

    /** With an eraser picked, the pen tip leaves no ink; its path still arrives as a stroke, which the app erases along. */
    override fun setTool(tool: PenTool) {
        this.tool = tool
        runCatching { helper?.setRawDrawingRenderEnabled(!tool.erases) }
    }

    override fun setPen(argb: Int, width: Float) {
        penArgb = argb; penWidth = width * STROKE_WIDTH / APP_WIDTH
        runCatching { helper?.setStrokeColor(argb)?.setStrokeWidth(penWidth) }
    }

    override fun detach() {
        runCatching { helper?.closeRawDrawing() }
        helper = null
    }

    override fun handleTouch(e: MotionEvent) = false

    companion object {
        private const val TAG = "NisabaPen"
        private const val STROKE_WIDTH = 3f   // the SDK's live ink at the app's medium pen…
        private const val APP_WIDTH = 3.5f    // …whose committed width is this
        private const val DEFAULT_MAX_PRESSURE = 4095f

        /** True on Boox devices where the Onyx SDK's system service is present. */
        fun isAvailable(): Boolean = runCatching {
            Class.forName("android.onyx.optimization.IOECService")
            true
        }.getOrDefault(false) || android.os.Build.MANUFACTURER.equals("ONYX", ignoreCase = true)
    }
}
