package com.nisaba.render

import android.graphics.Canvas
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ink-draw reveal (docs/08 §5): play/skip lifecycle, discrete steps,
 * and clean rendering to a canvas at every progress value.
 */
@RunWith(AndroidJUnit4::class)
class InkRevealTest {

    private val handler = Handler(Looper.getMainLooper())

    @Test
    fun reveal_playsStepsInOrder_andFinishes() {
        val reveal = InkReveal(handler, stepMs = 20L)
        val shown = mutableListOf<Int>()
        var done = false
        reveal.start(
            listOf(
                InkReveal.Step(show = { shown.add(1) }),
                InkReveal.Step(show = { shown.add(2) }),
                InkReveal.Step(show = { shown.add(3) }),
            ),
            onDone = { done = true },
        )
        Thread.sleep(300)
        assertEquals(listOf(1, 2, 3), shown)
        assertTrue(done)
        assertFalse(reveal.isActive())
    }

    @Test
    fun skip_runsRemainingShowsAndCancels() {
        val reveal = InkReveal(handler, stepMs = 1000L) // slow cadence
        val shown = mutableListOf<Int>()
        var cancelled = 0
        reveal.start(
            listOf(
                InkReveal.Step(show = { shown.add(1) }),
                InkReveal.Step(show = { shown.add(2) }, cancel = { cancelled++ }),
                InkReveal.Step(show = { shown.add(3) }, cancel = { cancelled++ }),
            ),
        )
        Thread.sleep(50)
        reveal.skip()
        assertEquals(listOf(1, 2, 3), shown)
        assertEquals(2, cancelled)
        assertFalse(reveal.isActive())
    }

    @Test
    fun emptySequence_completesImmediately() {
        val reveal = InkReveal(handler)
        var done = false
        reveal.start(emptyList(), onDone = { done = true })
        assertTrue(done)
    }
}

/**
 * DrawingInkView smoke: parses a drawing, plays and skips, and draws at
 * partial progress without throwing.
 */
@RunWith(AndroidJUnit4::class)
class DrawingInkViewTest {

    private fun view(): DrawingInkView {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        return DrawingInkView(ctx).apply {
            layout(0, 0, 800, 800)
        }
    }

    private fun sampleItems(): List<SvgInkRenderer.DrawItem> = SvgInkRenderer.parse(
        """<svg viewBox="0 0 1000 1000">
           <path d="M100 900 C300 100 700 100 900 900" stroke="#3A2E25" stroke-width="8"/>
           <circle cx="500" cy="500" r="200" fill="#3A2E25"/>
        </svg>"""
    )!!

    @Test
    fun playThenSkip_lifecycleIsClean() {
        val v = view()
        v.setDrawing(sampleItems())
        var done = false
        v.play(durationMs = 2000L, onDone = { done = true })
        assertTrue(v.isAnimating())
        v.skipToEnd()
        assertFalse(v.isAnimating())
        assertTrue(done)
    }

    @Test
    fun drawsAtPartialAndFullProgress() {
        val v = view()
        v.setDrawing(sampleItems())
        // Force an onDraw at progress ~0.5 by playing briefly, then drawing.
        v.play(durationMs = 4000L)
        Thread.sleep(300)
        val bmp = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp)) // must not throw at partial progress
        v.skipToEnd()
        v.draw(Canvas(bmp)) // must not throw at full progress
        assertFalse(v.isAnimating())
    }

    @Test
    fun emptyDrawing_isNoop() {
        val v = view()
        var done = false
        v.play(onDone = { done = true })
        assertTrue(done)
    }
}
