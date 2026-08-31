package com.nisaba.ink

import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.notebook.model.NotebookPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real input pipeline: stylus capture, pressure recording,
 * finger rejection and hover-gated palm rejection (docs/04 §4–5).
 * Events are built with explicit sources so getToolType() classifies them
 * exactly like a real EMR pen / touchscreen.
 */
@RunWith(AndroidJUnit4::class)
class InkSurfaceViewTest {

    private lateinit var view: InkSurfaceView
    private val page = NotebookPage(index = 0, width = 2480, height = 1860)
    private val completed = mutableListOf<com.nisaba.notebook.model.Stroke>()

    @Before
    fun setUp() {
        ActivityScenario.launch(TestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = InkSurfaceView(activity).apply {
                    layout(0, 0, 2400, 1800)
                    this.page = this@InkSurfaceViewTest.page
                    // Mirror NotebookScreen wiring: pen strokes land on the page.
                    onStrokeComplete = { stroke ->
                        completed.add(stroke)
                        if (stroke.tool == "pen") {
                            val p = this@InkSurfaceViewTest.page
                            p.strokes = p.strokes + stroke
                        }
                    }
                }
                activity.setContentView(view)
            }
        }
    }

    private fun ev(
        action: Int,
        x: Float,
        y: Float,
        pressure: Float = 0.6f,
        source: Int = InputDevice.SOURCE_STYLUS,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        button: Int = 0,
    ): MotionEvent {
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = pressure
            this.size = pressure
        }
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            this.toolType = toolType
        }
        return MotionEvent.obtain(
            100L /* downTime */, 200L /* eventTime */, action, 1 /* pointerCount */,
            arrayOf(props), arrayOf(coords), 0 /* metaState */, button /* buttonState */,
            1f, 1f, 0 /* deviceId */, 0 /* edgeFlags */, source, 0 /* flags */
        )
    }

    @Test
    fun stylusStroke_isCapturedWithPressure() {
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 100f, 100f, 0.2f))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 300f, 200f, 0.6f))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 500f, 300f, 1.0f))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 500f, 300f, 1.0f))

        assertEquals(1, page.strokes.size)
        val stroke = page.strokes[0]
        assertEquals("pen", stroke.tool)
        assertEquals(3, stroke.points.size)
        assertTrue("p0=${stroke.points[0].p}", stroke.points[0].p == 0.2f)
        assertTrue("p2=${stroke.points[2].p}", stroke.points[2].p == 1.0f)
        // Page-space mapping through the transform: order preserved
        // (absolute placement is PageTransform's concern — see PageTransformTest).
        val last = stroke.points[2]
        val first = stroke.points[0]
        assertTrue("x: ${first.x}->${last.x}", last.x > first.x)
        assertTrue("y: ${first.y}->${last.y}", last.y > first.y)
    }

    @Test
    fun fingerTouch_neverDraws() {
        val source = InputDevice.SOURCE_TOUCHSCREEN
        val tool = MotionEvent.TOOL_TYPE_FINGER
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 200f, 200f, source = source, toolType = tool))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 600f, 600f, source = source, toolType = tool))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 600f, 600f, source = source, toolType = tool))

        assertEquals(0, page.strokes.size)
    }

    @Test
    fun fingerTouchWhilePenHovering_isRejectedAsPalm() {
        // Pen enters range, then a palm (touchscreen) lands.
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_HOVER_ENTER, 1200f, 900f))
        val source = InputDevice.SOURCE_TOUCHSCREEN
        val tool = MotionEvent.TOOL_TYPE_FINGER
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 200f, 200f, source = source, toolType = tool))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 600f, 600f, source = source, toolType = tool))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 600f, 600f, source = source, toolType = tool))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_HOVER_EXIT, 1200f, 900f))

        assertEquals(0, page.strokes.size)
    }

    @Test
    fun penSideButton_turnsStrokeIntoEraser() {
        // Button held before contact (realistic: pressed, then touch down).
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 100f, 100f, button = MotionEvent.BUTTON_STYLUS_PRIMARY))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 150f, 150f, button = MotionEvent.BUTTON_STYLUS_PRIMARY))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 200f, 200f, button = MotionEvent.BUTTON_STYLUS_PRIMARY))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 200f, 200f))

        assertEquals(1, completed.size)
        assertEquals("eraser", completed[0].tool)
        assertEquals(0, page.strokes.size) // eraser strokes never land on the page
    }

    @Test
    fun penButtonPressedMidStroke_switchesToEraser() {
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 100f, 100f))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 150f, 150f))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 200f, 200f, button = MotionEvent.BUTTON_STYLUS_PRIMARY))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 250f, 250f, button = MotionEvent.BUTTON_STYLUS_PRIMARY))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 250f, 250f))

        assertEquals(1, completed.size)
        assertEquals("eraser", completed[0].tool)
    }

    @Test
    fun eraserActive_turnsStrokeIntoEraser() {
        view.eraserActive = true
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 100f, 100f))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 200f, 200f))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 200f, 200f))

        assertEquals(1, completed.size)
        assertEquals("eraser", completed[0].tool)
    }

    @Test
    fun shortStroke_isDiscarded() {
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 100f, 100f))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 100f, 100f))
        assertEquals(0, page.strokes.size)
    }
}
