package com.nisaba.canvas

import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.PageId
import com.nisaba.scribe.motion.TimedStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScribeAnimatorTest {
    private class FakeCanvas : ScribeCanvas {
        val drawnYs = mutableListOf<Float>()
        override fun drawSegment(points: InkPoints, from: Int, toExclusive: Int, argb: Int, widthScale: Float) {
            for (i in from until toExclusive) drawnYs += points.ys[i]
        }
        override fun cleanRegion(b: FloatArray) = Unit
    }

    private class ManualFrames : ScribeFrames {
        var time = 1_000L
        private var pending: (() -> Unit)? = null
        override fun now() = time
        override fun post(frame: () -> Unit) { pending = frame }
        override fun cancel() { pending = null }
        fun run(ms: Long) {
            val end = time + ms
            while (time < end) { time += 16; pending?.also { pending = null }?.invoke() } // one LCD frame
        }
    }

    private val canvas = FakeCanvas()
    private val frames = ManualFrames()
    private val animator = ScribeAnimator(canvas, frames)
    private val active = mutableListOf<Boolean>()
    private val drained = mutableListOf<Pair<Int, BlockRef?>>()
    private val breaks = mutableListOf<Int>()

    init {
        animator.onActiveChanged = { active += it }
        animator.onDrained = { w, ref -> drained += w.size to ref }
        animator.onPageBreak = { _, to -> breaks += to }
    }

    /** A short stroke on answer page [page], drawn between [t0] and t0+100 ms; y encodes the page so draws can be told apart. */
    private fun stroke(page: Int, t0: Int) = TimedStroke(
        page, InkPoints(FloatArray(5) { it * 10f }, FloatArray(5) { page * 1000f }, FloatArray(5) { .5f }, IntArray(5) { t0 + it * 25 }),
        false, 0,
    )

    private fun ref(seq: Int, req: Int = 1) = BlockRef(1, req, seq, ExchangeId("x$req"))
    private val p0 = PageId("p0")
    private val p1 = PageId("p1")

    @Test fun eachFinishedBlockIsCommittedWhileTheRestIsStillBeingWritten() {
        val committed = mutableListOf<Pair<Int, Int?>>()
        animator.onBlockWritten = { w, ref -> committed += w.size to ref?.seq }
        animator.enqueue(listOf(stroke(0, 0), stroke(0, 100)), ref(1), p0)
        animator.enqueue(listOf(stroke(0, 300)), ref(2), p0)
        frames.run(350) // a 120 ms lead-in, then block 1 (0–200 ms); block 2 starts at 300 ms
        assertEquals(listOf<Pair<Int, Int?>>(2 to 1), committed, "block 1 saved as soon as it is written")
        frames.run(400)
        assertEquals(1, committed.size); assertEquals(listOf<Pair<Int, BlockRef?>>(1 to ref(2)), drained) // last block: onDrained
    }

    @Test fun aFollowUpOnTheSpillPageStartsOnItsOwnPage() { // it used to wait forever for a turn back to "page 0"
        animator.enqueue(listOf(stroke(0, 0), stroke(1, 200)), ref(1), p0)
        frames.run(400); animator.pageReady(1, p1); frames.run(400)
        assertEquals(listOf(1), breaks); assertEquals(1, drained.size); assertFalse(animator.isActive)
        animator.enqueue(listOf(stroke(0, 0)), ref(1, req = 2), p1) // the next question, written on the spill page
        frames.run(400)
        assertEquals(listOf(1), breaks, "no page break for an answer that starts on its own page")
        assertEquals(2, drained.size); assertEquals(p1, animator.writingOn)
    }

    @Test fun anAnswerFromANewPageSessionIsANewAnswer() {
        animator.enqueue(listOf(stroke(0, 0), stroke(1, 200)), ref(1), p0)
        frames.run(400); animator.pageReady(1, p1); frames.run(400)
        animator.enqueue(listOf(stroke(0, 0)), BlockRef(2, 1, 1, ExchangeId("y")), p1) // same reqId, another page's session
        frames.run(400)
        assertEquals(listOf(1), breaks); assertEquals(2, drained.size)
    }

    @Test fun spillingToANewPageSuspendsAndResumesThePenExactlyOnce() {
        animator.enqueue(listOf(stroke(0, 0), stroke(1, 200)), ref(1), p0)
        frames.run(400)
        assertEquals(listOf(1), breaks)
        assertTrue(animator.isActive, "still in charge while the page turns")
        animator.pageReady(1, p1)
        assertEquals(p1, animator.writingOn)
        frames.run(1_000)
        assertEquals(listOf(true, false), active)
        assertEquals(1, drained.single().first)
        assertEquals(ref(1), drained.single().second)
    }

    @Test fun stoppingOrResettingWhileThePageTurnsReleasesThePen() {
        animator.enqueue(listOf(stroke(0, 0), stroke(1, 200)), ref(1), p0)
        frames.run(400)
        animator.stop()
        assertEquals(listOf(true, false), active)
        animator.pageReady(1, p1)                                             // late: stays stopped
        frames.run(1_000)
        assertEquals(listOf(true, false), active)
        animator.reset()
        assertEquals(listOf(true, false), active)
    }

    @Test fun skippingDrawsPageByPage() {
        animator.enqueue(listOf(stroke(0, 0), stroke(1, 200)), ref(1), p0)
        frames.run(50)
        animator.skipToEnd()
        assertEquals(listOf(1), breaks)
        assertTrue(canvas.drawnYs.all { it == 0f }, "nothing of page 2 drawn on page 1")
        animator.pageReady(1, p1)
        assertTrue(canvas.drawnYs.contains(1000f))
        assertEquals(1, drained.size)
        assertEquals(listOf(true, false), active)
    }

    @Test fun anEmptyBlockStillCountsAsWritten() {
        animator.enqueue(listOf(stroke(0, 0)), ref(1), p0)
        frames.run(500)
        animator.enqueue(emptyList(), ref(2), p0)
        assertEquals(listOf(ref(1), ref(2)), drained.map { it.second })
    }

    @Test fun aNewAnswerAfterAStoppedOneIsWrittenAndTheOldRestDropped() {
        animator.enqueue(listOf(stroke(0, 0), stroke(0, 1_000)), ref(1, req = 1), p0)
        frames.run(200)
        animator.stop()
        animator.enqueue(listOf(stroke(0, 0)), ref(1, req = 2), p0)
        frames.run(1_000)
        assertFalse(animator.isActive)
        assertEquals(ref(1, req = 2), drained.last().second)
        assertEquals(1, drained.size)
        assertEquals(listOf(true, false, true, false), active)
    }
}
