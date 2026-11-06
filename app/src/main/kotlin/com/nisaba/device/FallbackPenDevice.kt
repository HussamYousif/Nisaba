package com.nisaba.device

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.nisaba.domain.ink.InkPoints
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The fast pen ([primary], the Onyx SDK) with a safety net: if it can't start (a firmware update broke the SDK), the
 * [fallback] (plain stylus MotionEvents; the app draws the live ink, a little slower) takes over for good, so writing
 * never stops working. Both write into one [sink], which is [events].
 */
class FallbackPenDevice(
    private val primary: PenDevice,
    private val fallback: PenDevice,
    sink: MutableSharedFlow<PenEvent>,
    private val onSwitch: () -> Unit = {},
) : PenDevice {
    override val events: SharedFlow<PenEvent> = sink
    private var active: PenDevice = primary
    private var callback: () -> Unit = {}
    private var tool = PenTool.Pen
    private var pen: Pair<Int, Float>? = null
    private var suspended = 0

    override val liveCount: Int get() = active.liveCount
    override fun liveSlice(from: Int, to: Int): InkPoints? = active.liveSlice(from, to)
    override var onLiveChanged: () -> Unit
        get() = callback
        set(value) { callback = value; primary.onLiveChanged = value; fallback.onLiveChanged = value }

    /** True once the fallback has taken over. */
    val fellBack: Boolean get() = active === fallback

    override fun attach(view: View, drawArea: Rect, exclude: List<Rect>) {
        active.attach(view, drawArea, exclude)
        if (active === primary && primary.failed) {
            active = fallback
            fallback.setTool(tool); pen?.let { (argb, width) -> fallback.setPen(argb, width) }
            repeat(suspended) { fallback.suspendRawInk() }
            fallback.attach(view, drawArea, exclude)
            onSwitch()
        }
    }

    override fun setExclusions(exclude: List<Rect>) = active.setExclusions(exclude)
    override fun suspendRawInk() { suspended++; active.suspendRawInk() }
    override fun resumeRawInk() {
        suspended = (suspended - 1).coerceAtLeast(0)
        active.resumeRawInk()
    }
    override fun setTool(tool: PenTool) { this.tool = tool; active.setTool(tool) }
    override fun setPen(argb: Int, width: Float) { pen = argb to width; active.setPen(argb, width) }
    override fun detach() = active.detach()
    override fun handleTouch(e: MotionEvent): Boolean = active.handleTouch(e)

    companion object {
        /** The Onyx pen backed by the MotionEvent pen, sharing one event stream. */
        fun onyx(onSwitch: () -> Unit = {}): FallbackPenDevice {
            val sink = MutableSharedFlow<PenEvent>(extraBufferCapacity = 64)
            return FallbackPenDevice(OnyxPenDevice(sink), MotionEventPenDevice(acceptFinger = false, flow = sink), sink, onSwitch)
        }
    }
}
