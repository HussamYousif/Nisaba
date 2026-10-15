package com.nisaba.device

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.nisaba.domain.ink.InkPoints
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FallbackPenDeviceTest {
    private class FakePen(private val failOnAttach: Boolean = false) : PenDevice {
        override val events = MutableSharedFlow<PenEvent>()
        override val liveCount = 0
        override fun liveSlice(from: Int, to: Int): InkPoints? = null
        override var onLiveChanged: () -> Unit = {}
        val calls = mutableListOf<String>()
        override var failed = false
        override fun attach(view: View, drawArea: Rect, exclude: List<Rect>) { calls += "attach"; failed = failOnAttach }
        override fun setExclusions(exclude: List<Rect>) { calls += "exclude" }
        override fun suspendRawInk() { calls += "suspend" }
        override fun resumeRawInk() { calls += "resume" }
        override fun setTool(tool: PenTool) { calls += "tool $tool" }
        override fun setPen(argb: Int, width: Float) { calls += "pen $width" }
        override fun detach() { calls += "detach" }
        override fun handleTouch(e: MotionEvent) = true.also { calls += "touch" }
    }

    private val view = View(null)

    @Test fun anOnyxPenThatCannotStartHandsOverToTheStandardPenWithItsSettings() {
        val onyx = FakePen(failOnAttach = true); val standard = FakePen()
        var switched = 0
        val pen = FallbackPenDevice(onyx, standard, MutableSharedFlow(), onSwitch = { switched++ })
        pen.setTool(PenTool.AreaEraser); pen.setPen(0, 5.5f); pen.suspendRawInk()
        pen.attach(view, Rect(), emptyList())
        assertTrue(pen.fellBack); assertEquals(1, switched)
        assertEquals(listOf("tool AreaEraser", "pen 5.5", "suspend", "attach"), standard.calls) // carries on as set up
        pen.setExclusions(emptyList())
        assertEquals("exclude", standard.calls.last())
        pen.attach(view, Rect(), emptyList()); assertEquals(1, switched) // for good: the Onyx pen is not retried
    }

    @Test fun aWorkingOnyxPenIsUsedAsIs() {
        val onyx = FakePen(); val standard = FakePen()
        val pen = FallbackPenDevice(onyx, standard, MutableSharedFlow())
        pen.attach(view, Rect(), emptyList()); pen.resumeRawInk()
        assertFalse(pen.fellBack)
        assertEquals(listOf("attach", "resume"), onyx.calls); assertTrue(standard.calls.isEmpty())
    }
}
