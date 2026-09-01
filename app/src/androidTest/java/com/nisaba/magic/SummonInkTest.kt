package com.nisaba.magic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.ink.InkSurfaceView
import com.nisaba.notebook.model.NotebookPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The organic ink narrative (docs/09 §3): a thread of ink flows from the last
 * stroke's tail to the answer locus, gathers into a blot, and beads travel
 * while waiting. Draws safely at every phase; lifecycle is clean.
 */
@RunWith(AndroidJUnit4::class)
class SummonInkTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun drawIsSafeAtEveryPhase() {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val path = SummonInk.buildThread(60f, 300f, 300f, 100f)
        val len = SummonInk.threadLength(path)
        for (p in listOf(0f, 0.3f, 0.7f, 1f)) {
            SummonInk.drawThread(canvas, path, len, p)
            SummonInk.drawBead(canvas, path, len, p)
        }
        SummonInk.drawBlot(canvas, 300f, 100f, 24f)
        SummonInk.drawRipple(canvas, 300f, 100f, 24f, 0.7f)
    }

    @Test
    fun threadReachesTheLocus() {
        val path = SummonInk.buildThread(60f, 300f, 300f, 100f)
        val bounds = android.graphics.RectF()
        path.computeBounds(bounds, true)
        assertTrue(bounds.contains(60f, 300f) || bounds.width() > 0f)
        assertTrue(bounds.right >= 290f) // reaches toward the locus
    }

    @Test
    fun inkViewNarrativeLifecycle() {
        val v = InkSurfaceView(ctx).apply {
            layout(0, 0, 800, 800)
            page = NotebookPage(index = 0, width = 2480, height = 1860)
        }
        val bmp = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)

        v.beginSummonInk(400f, 600f, 400f, 300f)
        assertTrue(v.isSummonInkActive)
        Thread.sleep(450)
        v.draw(Canvas(bmp)) // thread drawing — must not throw

        v.endSummonInk(keep = true) // answer arrives: thread + blot stay
        assertTrue(v.isSummonInkActive)
        v.draw(Canvas(bmp)) // settled pose — must not throw

        v.beginSummonInk(400f, 600f, 400f, 300f)
        v.endSummonInk(keep = false) // cancel: thread recedes
        Thread.sleep(400)
        v.draw(Canvas(bmp))
        assertFalse(v.isSummonInkActive) // fully receded
    }
}
