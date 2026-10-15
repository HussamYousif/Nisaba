package com.nisaba.canvas

import android.os.SystemClock
import android.view.Choreographer
import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.PageId
import com.nisaba.scribe.layout.InkTone
import com.nisaba.scribe.motion.TimedStroke
import com.nisaba.scribe.motion.pointsReachedBy

/** What the hand draws on (the [PageView]). */
interface ScribeCanvas {
    fun drawSegment(points: InkPoints, from: Int, toExclusive: Int, argb: Int, widthScale: Float = 1f)
    fun cleanRegion(b: FloatArray)
}

/** Frame pacing: the Choreographer on the device, a manual clock in tests. */
interface ScribeFrames {
    fun now(): Long
    fun post(frame: () -> Unit)
    fun cancel()
}

class ChoreographerFrames : ScribeFrames {
    private var tick: () -> Unit = {}
    private val callback = Choreographer.FrameCallback { tick() }
    override fun now(): Long = SystemClock.uptimeMillis()
    override fun post(frame: () -> Unit) { tick = frame; Choreographer.getInstance().postFrameCallback(callback) }
    override fun cancel() = Choreographer.getInstance().removeFrameCallback(callback)
}

/**
 * Which answer block strokes came from: the page session that asked ([session]), its request, the block's 1-based
 * number within it, and the exchange the answer is saved under.
 */
data class BlockRef(val session: Int, val reqId: Int, val seq: Int, val exchangeId: ExchangeId)

/**
 * Plays timed pen strokes onto a [ScribeCanvas] at ~20 fps, one letter at a time. Blocks can be [enqueue]d while playing
 * (the model is still talking). [stop] freezes the hand and returns what has been written so far; [resume] carries on.
 * Everything drawn here is committed by the caller through [onDrained] / [onPageBreak] / [stop].
 *
 * "Active" (the raw pen layer is suspended, the panel is fast) spans the whole answer including the gap while the caller
 * turns the page for a spill; [onActiveChanged] is called exactly once per transition.
 */
class ScribeAnimator(private val view: ScribeCanvas, private val frames: ScribeFrames = ChoreographerFrames()) {
    private val queue = ArrayList<TimedStroke>()
    private var strokeIdx = 0
    private var pointIdx = 0            // points of queue[strokeIdx] already drawn
    private var pieceStart = 0          // where the not-yet-committed piece of queue[strokeIdx] begins
    private var t = 0f                  // plan-time playhead in ms
    private var lastFrameMs = 0L
    private var playing = false
    private var active = false
    private var awaitingPage = false     // handed over at a page break; waiting for [pageReady]
    private var skipping = false         // a tap asked for everything at once (carries on across page breaks)
    private var page = 0                 // which page of the answer is being written (0 = the question's page)
    private var paused = false

    /** The block most recently handed to the hand (kept after it finishes, cleared by [reset]). */
    var lastRef: BlockRef? = null
        private set

    /** The page the hand is writing on (follows spills via [pageReady]); what it wrote belongs there. */
    var writingOn: PageId? = null
        private set
    private val written = ArrayList<TimedStroke>()

    /** Index of each queued block's last stroke in [queue], and the block, oldest first. */
    private val blockEnds = ArrayDeque<Pair<Int, BlockRef?>>()
    private var bounds: FloatArray? = null // minX, minY, maxX, maxY of everything drawn in this run

    /** Called when the hand starts/stops being in charge of the page (the screen suspends raw ink, switches refresh). */
    var onActiveChanged: (Boolean) -> Unit = {}

    /** Called when every planned stroke has been drawn: the strokes written since the last commit, and the last block. */
    var onDrained: (List<TimedStroke>, BlockRef?) -> Unit = { _, _ -> }

    /** A block is written in full while more is queued: commit it now, so a long answer is saved as it is written. */
    var onBlockWritten: (List<TimedStroke>, BlockRef?) -> Unit = { _, _ -> }

    /** The next stroke belongs to a later page: hand over what was written; the caller turns the page, then [pageReady]. */
    var onPageBreak: (written: List<TimedStroke>, toPage: Int) -> Unit = { _, _ -> }

    /** The pen for each stroke's tone (answer ink; highlighter tones in code). */
    var penFor: (InkTone) -> AnswerPen = { AnswerPen(com.nisaba.domain.ink.InkColor.LAPIS, 1f) }
    val isActive: Boolean get() = active

    private val idle get() = !playing && !awaitingPage && strokeIdx >= queue.size

    fun enqueue(strokes: List<TimedStroke>, ref: BlockRef? = null, pageId: PageId? = null) {
        if ((paused || idle) && startsNewAnswer(ref)) {
            // a new answer starts on its own question's page (an earlier one may have spilled onto later pages); after
            // one the pen stopped, whatever the old one still had planned is never written
            clearQueue(); written.clear(); paused = false; awaitingPage = false; page = 0
        }
        val wasIdle = idle
        if (ref != null) lastRef = ref
        if (wasIdle && pageId != null) writingOn = pageId
        if (strokes.isEmpty()) {
            if (wasIdle && !paused) onDrained(emptyList(), lastRef) // an empty block: nothing to write, still "written"
            return
        }
        queue += strokes
        blockEnds.addLast(queue.lastIndex to ref)
        if (wasIdle) { t = strokes.first().startMs - LEAD_MS.toFloat(); lastFrameMs = 0L }
        if (!paused) start()
    }

    private fun startsNewAnswer(ref: BlockRef?): Boolean {
        val last = lastRef
        return ref != null && last != null && (ref.reqId != last.reqId || ref.session != last.session)
    }

    private fun clearQueue() { queue.clear(); blockEnds.clear(); strokeIdx = 0; pointIdx = 0; pieceStart = 0 }

    fun resume() {
        if (!paused) return
        paused = false; lastFrameMs = 0L
        if (strokeIdx >= queue.size && !awaitingPage) finish() else start()
    }

    /** The caller has turned to [toPage]; carry on writing there. */
    fun pageReady(toPage: Int, pageId: PageId? = null) {
        if (!awaitingPage) return
        awaitingPage = false
        page = toPage
        if (pageId != null) writingOn = pageId
        lastFrameMs = 0L
        when {
            paused -> Unit
            skipping -> skipToEnd()
            strokeIdx < queue.size -> start()
            else -> finish()
        }
    }

    /** Freezes the hand. Returns strokes (whole and the partial one) written since the last commit; they are cleared. */
    fun stop(): List<TimedStroke> {
        frames.cancel()
        playing = false; paused = true; skipping = false
        val out = ArrayList(written); written.clear()
        if (strokeIdx < queue.size && pointIdx > pieceStart) {
            val s = queue[strokeIdx]
            out += TimedStroke(s.page, s.points.slice(pieceStart, pointIdx), s.bold, s.wordIndex, s.tone)
            pieceStart = maxOf(0, pointIdx - 1)
        }
        setActive(false)
        return out
    }

    /** Drops everything: no commit, nothing more is drawn (call [stop] first to keep what was written). */
    fun reset() {
        frames.cancel()
        playing = false; paused = false; awaitingPage = false; skipping = false
        clearQueue(); written.clear(); bounds = null; page = 0; lastRef = null; writingOn = null
        setActive(false)
    }

    /** Draws everything still planned at once (a tap on the answer), page by page. */
    fun skipToEnd() {
        if (!active || paused) return
        skipping = true
        if (awaitingPage) return
        frames.cancel(); playing = false
        while (strokeIdx < queue.size) {
            val s = queue[strokeIdx]
            if (s.page != page) { breakForPage(s.page); return }
            if (pointIdx < s.points.size) draw(s, pointIdx, s.points.size)
            complete(s)
        }
        finish()
    }

    private fun start() {
        if (playing || awaitingPage || strokeIdx >= queue.size) return
        playing = true
        setActive(true)
        frames.post(::tick)
    }

    private fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        onActiveChanged(on)
    }

    private fun tick() {
        if (!playing) return
        val now = frames.now()
        if (lastFrameMs == 0L) lastFrameMs = now - FRAME_MS
        val dt = now - lastFrameMs
        if (dt < FRAME_MS) { frames.post(::tick); return }
        lastFrameMs = now; t += dt
        advance()
        when {
            !playing -> Unit // handed over at a page break
            strokeIdx >= queue.size -> finish()
            else -> frames.post(::tick)
        }
    }

    private fun advance() {
        while (strokeIdx < queue.size) {
            val s = queue[strokeIdx]
            if (s.page != page) { breakForPage(s.page); return }
            val reached = pointsReachedBy(s, t.toInt())
            if (reached > pointIdx) draw(s, pointIdx, reached)
            if (pointIdx >= s.points.size) complete(s) else break
        }
    }

    private fun draw(s: TimedStroke, from: Int, toExclusive: Int) {
        val pen = penFor(s.tone)
        view.drawSegment(s.points, maxOf(0, from - 1), toExclusive, pen.color.argb, pen.widthScale)
        pointIdx = toExclusive
        val p = s.points
        val b = bounds ?: floatArrayOf(p.xs[0], p.ys[0], p.xs[0], p.ys[0]).also { bounds = it }
        for (i in from until toExclusive) {
            b[0] = minOf(b[0], p.xs[i]); b[1] = minOf(b[1], p.ys[i]); b[2] = maxOf(b[2], p.xs[i]); b[3] = maxOf(b[3], p.ys[i])
        }
    }

    private fun breakForPage(toPage: Int) {
        frames.cancel()
        playing = false; awaitingPage = true // still active: the hand only pauses while the page turns
        val out = ArrayList(written); written.clear()
        bounds?.let { view.cleanRegion(it) }; bounds = null
        onPageBreak(out, toPage)
    }

    private fun complete(s: TimedStroke) {
        written += if (pieceStart == 0) s else TimedStroke(s.page, s.points.slice(pieceStart, s.points.size), s.bold, s.wordIndex, s.tone)
        strokeIdx++; pointIdx = 0; pieceStart = 0
        while (blockEnds.isNotEmpty() && blockEnds.first().first < strokeIdx) {
            val ref = blockEnds.removeFirst().second
            // the last block is committed by finish() (with the "done" report); earlier ones as soon as they are written
            if (strokeIdx < queue.size && written.isNotEmpty()) { val out = ArrayList(written); written.clear(); onBlockWritten(out, ref) }
        }
    }

    private fun finish() {
        frames.cancel()
        playing = false; skipping = false
        clearQueue()
        val out = ArrayList(written); written.clear()
        bounds?.let { view.cleanRegion(it) }; bounds = null
        setActive(false)
        onDrained(out, lastRef)
    }

    private companion object {
        const val FRAME_MS = 50L
        const val LEAD_MS = 120
    }
}
