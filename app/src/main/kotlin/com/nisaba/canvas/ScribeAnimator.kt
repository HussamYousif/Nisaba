package com.nisaba.canvas

import android.os.SystemClock
import android.view.Choreographer
import com.nisaba.domain.shared.PagePoint
import com.nisaba.scribe.motion.TimedStroke
import com.nisaba.scribe.motion.pointsReachedBy
import com.nisaba.scribe.motion.truncateAt

/**
 * Plays timed pen strokes onto a [PageView] at ~20 fps, one letter at a time. Blocks can be [enqueue]d while playing
 * (the model is still talking). [stop] freezes the hand and returns what has been written so far; [resume] carries on.
 * Everything drawn here is committed by the caller through [onDrained] / [stop].
 */
class ScribeAnimator(private val view: PageView) {
    private val queue = ArrayList<TimedStroke>()
    private var strokeIdx = 0
    private var pointIdx = 0            // points of queue[strokeIdx] already drawn
    private var pieceStart = 0          // where the not-yet-committed piece of queue[strokeIdx] begins
    private var t = 0f                  // plan-time playhead in ms
    private var lastFrameMs = 0L
    private var playing = false
    private var page = 0                 // which page of the answer is being written (0 = the question's page)
    private var paused = false
    private val written = ArrayList<TimedStroke>()
    private var bounds: FloatArray? = null // minX, minY, maxX, maxY of everything drawn in this run

    /** Called when the hand starts/stops moving (the screen switches the panel to fast refresh and suspends raw ink). */
    var onActiveChanged: (Boolean) -> Unit = {}

    /** Called when every planned stroke has been drawn: the strokes written since the last commit. */
    var onDrained: (List<TimedStroke>) -> Unit = {}

    /** The next stroke belongs to a later page: hand over what was written; the caller turns the page, then [pageReady]. */
    var onPageBreak: (written: List<TimedStroke>, toPage: Int) -> Unit = { _, _ -> }

    var color: Int = 0xFF27407A.toInt()
    val isActive: Boolean get() = playing

    private val frame = Choreographer.FrameCallback { tick() }

    fun enqueue(strokes: List<TimedStroke>) {
        if (strokes.isEmpty()) return
        val idle = !playing && strokeIdx >= queue.size
        queue += strokes
        if (idle) { t = strokes.first().startMs - LEAD_MS.toFloat(); lastFrameMs = 0L }
        if (!paused) start()
    }

    fun resume() { if (paused) { paused = false; lastFrameMs = 0L; start() } }

    /** The caller has turned to [toPage]; carry on writing there. */
    fun pageReady(toPage: Int) {
        page = toPage
        lastFrameMs = 0L
        if (!paused && strokeIdx < queue.size) { playing = false; start() }
    }

    /** Freezes the hand. Returns strokes (whole and the partial one) written since the last commit; they are cleared. */
    fun stop(): List<TimedStroke> {
        Choreographer.getInstance().removeFrameCallback(frame)
        val wasPlaying = playing
        playing = false; paused = true
        val out = ArrayList(written); written.clear()
        if (strokeIdx < queue.size && pointIdx > pieceStart) {
            val s = queue[strokeIdx]
            out += TimedStroke(s.page, s.points.slice(pieceStart, pointIdx), s.bold, s.wordIndex)
            pieceStart = maxOf(0, pointIdx - 1)
        }
        if (wasPlaying) onActiveChanged(false)
        return out
    }

    /** Drops everything (page turn): no commit, nothing more is drawn. */
    fun reset() {
        Choreographer.getInstance().removeFrameCallback(frame)
        val wasPlaying = playing
        playing = false; paused = false
        queue.clear(); written.clear(); strokeIdx = 0; pointIdx = 0; pieceStart = 0; bounds = null; page = 0
        if (wasPlaying) onActiveChanged(false)
    }

    /** Draws everything still planned at once (a tap on the answer). */
    fun skipToEnd() {
        while (strokeIdx < queue.size) {
            val s = queue[strokeIdx]
            if (pointIdx < s.points.size) draw(s, pointIdx, s.points.size)
            complete(s)
        }
        finish()
    }

    private fun start() {
        if (playing || strokeIdx >= queue.size) return
        playing = true
        onActiveChanged(true)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun tick() {
        if (!playing) return
        val now = SystemClock.uptimeMillis()
        if (lastFrameMs == 0L) lastFrameMs = now - FRAME_MS
        val dt = now - lastFrameMs
        if (dt < FRAME_MS) { Choreographer.getInstance().postFrameCallback(frame); return }
        lastFrameMs = now; t += dt
        advance()
        when {
            strokeIdx >= queue.size -> finish()
            playing -> Choreographer.getInstance().postFrameCallback(frame)
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
        view.drawSegment(s.points, maxOf(0, from - 1), toExclusive, color)
        pointIdx = toExclusive
        val p = s.points
        val b = bounds ?: floatArrayOf(p.xs[0], p.ys[0], p.xs[0], p.ys[0]).also { bounds = it }
        for (i in from until toExclusive) {
            b[0] = minOf(b[0], p.xs[i]); b[1] = minOf(b[1], p.ys[i]); b[2] = maxOf(b[2], p.xs[i]); b[3] = maxOf(b[3], p.ys[i])
        }
    }

    private fun breakForPage(toPage: Int) {
        Choreographer.getInstance().removeFrameCallback(frame)
        playing = false
        val out = ArrayList(written); written.clear()
        bounds?.let { view.cleanRegion(it) }; bounds = null
        onPageBreak(out, toPage)
    }

    private fun complete(s: TimedStroke) {
        written += if (pieceStart == 0) s else TimedStroke(s.page, s.points.slice(pieceStart, s.points.size), s.bold, s.wordIndex)
        strokeIdx++; pointIdx = 0; pieceStart = 0
    }

    private fun finish() {
        Choreographer.getInstance().removeFrameCallback(frame)
        playing = false
        val out = ArrayList(written); written.clear()
        bounds?.let { view.cleanRegion(it) }; bounds = null
        onActiveChanged(false)
        onDrained(out)
    }

    /** Truncates the queue view of a stroke at the current playhead (used by tests/tools). */
    internal fun writtenSoFar(): TimedStroke? = queue.getOrNull(strokeIdx)?.let { truncateAt(it, t.toInt()) }

    private companion object {
        const val FRAME_MS = 50L
        const val LEAD_MS = 120
    }
}
