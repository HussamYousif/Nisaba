package com.nisaba.device

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.shared.PagePoint
import kotlinx.coroutines.flow.SharedFlow

/** [Shape] draws like the pen; the stroke is then replaced by a clean shape. [Lasso] picks ink with a loop. */
enum class PenTool {
    Pen, Shape, StrokeEraser, AreaEraser, Lasso;

    val erases: Boolean get() = this == StrokeEraser || this == AreaEraser

    /** The pen tip leaves ink (else its path only arrives as a stroke for the app to act on). */
    val inks: Boolean get() = this == Pen || this == Shape
}

/** Coordinates are VIEW pixels; the ViewModel maps them to page units. */
sealed interface PenEvent {
    data class PenDown(val x: Float, val y: Float) : PenEvent
    data class StrokeDone(val points: InkPoints) : PenEvent
    data class EraseDone(val path: List<PagePoint>) : PenEvent
}

/** Source of stylus strokes. Vanilla has one: plain MotionEvents ([MotionEventPenDevice]); the Boox build adds Onyx raw drawing. */
interface PenDevice {
    val events: SharedFlow<PenEvent>

    /** Points of the stroke being drawn RIGHT NOW by the app itself (0 when the device draws its own live ink). */
    val liveCount: Int

    /** View-pixel points [from, to) of the live stroke. */
    fun liveSlice(from: Int, to: Int): InkPoints?

    /** The view sets this; called whenever [liveCount] changes (including back to 0 when the stroke ends). */
    var onLiveChanged: () -> Unit

    fun attach(view: View, drawArea: Rect, exclude: List<Rect>)
    fun setExclusions(exclude: List<Rect>)

    /** Ref-counted: while suspended the app may refresh/animate the panel without the raw layer interfering. */
    fun suspendRawInk()
    fun resumeRawInk()
    fun setTool(tool: PenTool)

    /** The live ink's colour (ARGB) and width (view px) so it matches what will be committed. */
    fun setPen(argb: Int, width: Float)
    fun detach()

    /** Called from the view's onTouchEvent. Returns true if the event was consumed as pen input. */
    fun handleTouch(e: MotionEvent): Boolean

    /** The device could not start (e.g. its SDK failed to attach): another one should take over. */
    val failed: Boolean get() = false
}
