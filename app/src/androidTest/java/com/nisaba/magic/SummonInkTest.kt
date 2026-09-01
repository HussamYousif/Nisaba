package com.nisaba.magic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.ink.InkSurfaceView
import com.nisaba.notebook.model.NotebookPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The organic ink narrative (docs/09 §3): a thread of ink wanders out of the
 * last stroke's tail toward the answer locus — never by the short way, and
 * leaving along the direction the pen was actually travelling — throws off
 * dead-end branches, and gathers into a breathing blot. When the answer
 * arrives the thread is drunk and the page is left clean.
 */
@RunWith(AndroidJUnit4::class)
class SummonInkTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun inked(w: Int, h: Int, block: (Canvas) -> Unit): Int {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        block(Canvas(bmp))
        var n = 0
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (px in row) if (px ushr 24 != 0) n++
        }
        return n
    }

    @Test
    fun drawIsSafeAtEveryPhase() {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val path = SummonInk.buildThread(60f, 300f, 300f, 100f)
        val len = SummonInk.threadLength(path)
        val roots = SummonInk.buildRoots(path, len, 60f, 300f, 300f, 100f)
        for (p in listOf(0f, 0.3f, 0.7f, 1f)) {
            SummonInk.drawThread(canvas, path, len, p)
            SummonInk.drawRoots(canvas, roots, p, 0f)
            SummonInk.drawBead(canvas, path, len, p)
            SummonInk.drawThreadDraining(canvas, path, len, p)
        }
        for (breath in listOf(-1f, 0f, 1f)) {
            SummonInk.drawBlot(canvas, 300f, 100f, 24f, breath)
        }
        for (deflate in listOf(0f, 0.5f, 1f)) {
            SummonInk.drawBlot(canvas, 300f, 100f, 24f, 0f, deflate)
        }
        SummonInk.drawRipple(canvas, 300f, 100f, 24f, 0.7f)
    }

    /** The wander must be deterministic — ink that jitters ghosts on e-ink. */
    @Test
    fun theWanderIsStableBetweenFrames() {
        val a = SummonInk.buildThread(60f, 300f, 300f, 100f, 0.9f, -0.4f)
        val b = SummonInk.buildThread(60f, 300f, 300f, 100f, 0.9f, -0.4f)
        assertEquals(SummonInk.threadLength(a), SummonInk.threadLength(b), 0.001f)
    }

    /**
     * Every summon passes a real exit tangent — the two lead-out points plus
     * start, waypoints and curl must fit the knot buffer (crash regression:
     * the original sizing held one point too few and every summon blew up
     * with ArrayIndexOutOfBoundsException).
     */
    @Test
    fun buildThread_withExitTangent_fitsItsKnotBuffer() {
        val path = SummonInk.buildThread(60f, 300f, 300f, 100f, 0.9f, -0.4f)
        val bounds = android.graphics.RectF()
        path.computeBounds(bounds, true)
        assertTrue("path must be non-empty", bounds.width() > 0f || bounds.height() > 0f)
    }

    /** However far it wanders, the thread stays pinned to stroke and locus. */
    @Test
    fun theThreadStaysPinnedToBothEnds() {
        val path = SummonInk.buildThread(60f, 300f, 300f, 100f)
        val measure = android.graphics.PathMeasure(path, false)
        val pos = FloatArray(2)
        measure.getPosTan(0f, pos, null)
        assertEquals(60f, pos[0], 1f)
        assertEquals(300f, pos[1], 1f)
        measure.getPosTan(measure.length, pos, null)
        assertEquals(300f, pos[0], 1f)
        assertEquals(100f, pos[1], 1f)
    }

    @Test
    fun threadReachesTheLocus() {
        val path = SummonInk.buildThread(60f, 300f, 300f, 100f)
        val bounds = android.graphics.RectF()
        path.computeBounds(bounds, true)
        assertTrue(bounds.contains(60f, 300f) || bounds.width() > 0f)
        assertTrue(bounds.right >= 290f) // reaches toward the locus
    }

    /**
     * The whole point: the ink seeks rather than aims. It must be meaningfully
     * longer than the straight line, and must stray meaningfully off it.
     */
    @Test
    fun theThreadDoesNotTakeTheShortWay() {
        val fromX = 60f; val fromY = 300f; val toX = 300f; val toY = 100f
        val straight = hypot(toX - fromX, toY - fromY)
        val path = SummonInk.buildThread(fromX, fromY, toX, toY, 0.94f, -0.34f)
        val len = SummonInk.threadLength(path)
        assertTrue("thread $len is barely longer than the straight $straight", len >= straight * 1.30f)

        // Largest sideways excursion from the straight line.
        val measure = android.graphics.PathMeasure(path, false)
        val pos = FloatArray(2)
        val ux = (toX - fromX) / straight
        val uy = (toY - fromY) / straight
        var worst = 0f
        for (i in 0..60) {
            measure.getPosTan(len * i / 60f, pos, null)
            val dev = abs((pos[0] - fromX) * -uy + (pos[1] - fromY) * ux)
            if (dev > worst) worst = dev
        }
        assertTrue("max deviation $worst is too tame for $straight", worst >= straight * 0.08f)
    }

    /**
     * It grows out of the word: the lead-out follows the pen's exit direction,
     * not a bearing on the locus.
     *
     * Sampled at a fraction of the lead-out itself (0.18·dist clamped to
     * 20..70, as buildThread computes it) rather than a fraction of the path —
     * over a long reach the 70 px lead-out is a small slice of a 1600 px path,
     * and a fixed 10% would sample well past the turn.
     */
    @Test
    fun theThreadLeavesAlongTheExitTangent() {
        for ((fx, fy, tx, ty) in listOf(
            listOf(60f, 300f, 300f, 100f),      // short reach: lead-out not clamped
            listOf(1200f, 400f, 190f, 900f),    // long reach: lead-out clamped to 70
        )) {
            val exitX = 0.94f; val exitY = -0.34f   // pen still moving right and up
            val path = SummonInk.buildThread(fx, fy, tx, ty, exitX, exitY)
            val reach = (hypot(tx - fx, ty - fy) * 0.18f).coerceIn(20f, 70f)
            val measure = android.graphics.PathMeasure(path, false)
            val pos = FloatArray(2)
            measure.getPosTan(reach * 0.7f, pos, null)
            val dx = pos[0] - fx
            val dy = pos[1] - fy
            val dl = hypot(dx, dy).coerceAtLeast(1e-3f)
            val el = hypot(exitX, exitY)
            val cos = (dx / dl) * (exitX / el) + (dy / dl) * (exitY / el)
            assertTrue("lead-out departs the exit tangent (cos=$cos)", cos > 0.86f) // ~30 deg
        }
    }

    /**
     * The ink creeps: many branches, not one thread — and the ones that leave
     * the word beside the channel are the false starts that sell the seeking.
     */
    @Test
    fun theInkCreepsInManyBranches() {
        val path = SummonInk.buildThread(60f, 300f, 300f, 100f, 0.94f, -0.34f)
        val len = SummonInk.threadLength(path)
        val roots = SummonInk.buildRoots(path, len, 60f, 300f, 300f, 100f, 0.94f, -0.34f)
        assertTrue("a root system, not a pair of hairs: ${'$'}{roots.size}", roots.size >= 10)

        // Several tips must be creeping from the very start — the false starts.
        val early = inked(600, 600) { SummonInk.drawRoots(it, roots, 0.03f, 0f) }
        assertTrue("tips should already be exploring at the tail", early > 0)
    }

    /** A branch is ink that tried another way: it vanishes when the drain passes. */
    @Test
    fun rootsVanishAsTheDrainPasses() {
        val path = SummonInk.buildThread(60f, 300f, 300f, 100f, 0.94f, -0.34f)
        val len = SummonInk.threadLength(path)
        val roots = SummonInk.buildRoots(path, len, 60f, 300f, 300f, 100f, 0.94f, -0.34f)

        val grown = inked(600, 600) { SummonInk.drawRoots(it, roots, 1f, 0f) }
        assertTrue("the root system should be drawn at full growth", grown > 0)

        val drunk = inked(600, 600) { SummonInk.drawRoots(it, roots, 1f, 1f) }
        assertEquals("every branch must be gone behind the drain front", 0, drunk)
    }

    /** Growth is ordered: the system is fuller later than it is early on. */
    @Test
    fun theRootSystemFillsInAsTheFrontAdvances() {
        val path = SummonInk.buildThread(60f, 300f, 300f, 100f, 0.94f, -0.34f)
        val len = SummonInk.threadLength(path)
        val roots = SummonInk.buildRoots(path, len, 60f, 300f, 300f, 100f, 0.94f, -0.34f)
        val early = inked(600, 600) { SummonInk.drawRoots(it, roots, 0.25f, 0f) }
        val late = inked(600, 600) { SummonInk.drawRoots(it, roots, 1f, 0f) }
        assertTrue("later growth must be fuller (${'$'}early -> ${'$'}late)", late > early * 2)
    }

    /** Nothing is left of the thread once the drain reaches the blot. */
    @Test
    fun theDrainLeavesNothing() {
        val path = SummonInk.buildThread(60f, 300f, 300f, 100f)
        val len = SummonInk.threadLength(path)
        assertEquals(0, inked(400, 400) { SummonInk.drawThreadDraining(it, path, len, 1f) })
        assertEquals(0, inked(400, 400) { SummonInk.drawBlot(it, 200f, 200f, 24f, 0f, 0f) })
    }

    /** Ink wicks in bursts: the advance is uneven and still sums to the whole. */
    @Test
    fun theReachHesitates() {
        val d = SummonInk.threadStepDeltas
        assertEquals(SummonInk.THREAD_STEPS, d.size)
        assertEquals(1f, d.sum(), 0.001f)
        assertTrue("every step must advance", d.all { it > 0f })
        assertTrue("the advance must be uneven", d.max() > d.min() * 3f)
    }

    @Test
    fun inkViewNarrativeLifecycle() {
        val v = InkSurfaceView(ctx).apply {
            layout(0, 0, 800, 800)
            page = NotebookPage(index = 0, width = 2480, height = 1860)
        }
        val bmp = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)

        v.beginSummonInk(400f, 600f, 400f, 300f, 0.9f, -0.4f)
        assertTrue(v.isSummonInkActive)
        Thread.sleep(450)
        v.draw(Canvas(bmp)) // thread drawing — must not throw

        // The answer arrives: the thread is drunk, and the page comes back clean.
        var drained = false
        v.onSummonInkDrained = { drained = true }
        v.endSummonInk(keep = true)
        assertTrue("the drain should still be animating", v.isSummonInkActive)
        v.draw(Canvas(bmp)) // mid-drain — must not throw
        Thread.sleep(1900)
        assertFalse("the reached-out ink must not be left on the page", v.isSummonInkActive)
        assertTrue("the answer waits for a clean page", drained)

        v.beginSummonInk(400f, 600f, 400f, 300f)
        v.endSummonInk(keep = false) // cancel: thread recedes tip-first
        Thread.sleep(400)
        v.draw(Canvas(bmp))
        assertFalse(v.isSummonInkActive) // fully receded
    }

    /**
     * Audit P0-7: the summon narrative is page-local. Turning the page
     * mid-summon must clear the thread and blot — nothing of the old page's
     * ink may follow — and a later endSummonInk must be a clean no-op.
     */
    @Test
    fun newPage_resetsTheSummonNarrative() {
        val v = InkSurfaceView(ctx).apply {
            layout(0, 0, 800, 800)
            page = NotebookPage(index = 0, width = 2480, height = 1860)
        }
        v.beginSummonInk(400f, 600f, 400f, 300f, 0.9f, -0.4f)
        assertTrue(v.isSummonInkActive)

        v.page = NotebookPage(index = 1, width = 2480, height = 1860)
        assertFalse("the blot must not follow you to the next page", v.isSummonInkActive)

        // The late keep/cancel from the old page's flow is a no-op now.
        v.endSummonInk(keep = true)
        assertFalse(v.isSummonInkActive)
        v.draw(Canvas(Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)))
    }

    /** Audit P0-3: the page transform stays fit-width — offsetY pinned at 0. */
    @Test
    fun pageSetter_keepsFitWidthAnchoring() {
        val v = InkSurfaceView(ctx).apply {
            layout(0, 0, 2400, 2400) // view taller than the page
            page = NotebookPage(index = 0, width = 2480, height = 1860)
        }
        assertEquals(
            "contain-fit would have centered the page (offsetY > 0)",
            0f, v.transformOffsetY, 1e-3f,
        )

        // And turning the page must not re-introduce the shift.
        v.page = NotebookPage(index = 1, width = 2480, height = 1860)
        assertEquals(0f, v.transformOffsetY, 1e-3f)
    }
}
