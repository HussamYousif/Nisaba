package com.nisaba.scribe.motion

import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.settings.HandSpeed
import com.nisaba.scribe.hand.LaidStroke
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

const val SAMPLE_MS = 8
private const val MAX_GAP_PX = 2.5f
private const val MIN_STROKE_MS = 35
private const val PEN_LIFT_MS = 25
private const val MIN_GAP_MS = 15
private const val WORD_PAUSE_MS = 60
private const val BLOCK_PAUSE_MS = 350
private const val DELAYED_STROKE_MS = 90
private const val DRAW_SHARE = 0.8f
private const val RULE_SPEED = 1.4f // page units per millisecond

fun speedProfile(speed: HandSpeed) = when (speed) {
    HandSpeed.Slow -> SpeedProfile(12f, 28f, 40, 120)
    HandSpeed.Natural -> SpeedProfile(20f, 45f, 40, 120)
    HandSpeed.Swift -> SpeedProfile(30f, 70f, 40, 120)
}

fun cps(profile: SpeedProfile, charsWritten: Int): Float {
    val k = ((charsWritten - profile.rampStartChars) / profile.rampLengthChars.toFloat()).coerceIn(0f, 1f)
    return profile.startCps + (profile.endCps - profile.startCps) * k
}

/** Minimum-jerk position profile: slow at pen-down and lift, fast mid-stroke. */
fun minimumJerk(tau: Float): Float {
    val t = tau.coerceIn(0f, 1f)
    return t * t * t * (10f + t * (-15f + 6f * t))
}

private fun length(xs: FloatArray, ys: FloatArray): Float {
    var l = 0f
    for (i in 1 until xs.size) l += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    return l
}

/** Resamples a polyline every [SAMPLE_MS] along a minimum-jerk timeline starting at [startMs]. */
fun sampleStroke(xs: FloatArray, ys: FloatArray, ps: FloatArray, startMs: Int, durationMs: Int): InkPoints {
    val n = xs.size
    if (n == 1 || durationMs <= 0) return InkPoints(xs.copyOf(), ys.copyOf(), ps.copyOf(), IntArray(n) { startMs })
    val cum = FloatArray(n)
    for (i in 1 until n) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    val total = cum[n - 1]
    if (total <= 0f) return InkPoints(floatArrayOf(xs[0]), floatArrayOf(ys[0]), floatArrayOf(ps[0]), intArrayOf(startMs))
    // Dense enough in space (fast pen strokes must not collapse curves into a few segments) and in time.
    val steps = max(max(2, durationMs / SAMPLE_MS + 1), (total / MAX_GAP_PX).toInt() + 2)
    val ox = FloatArray(steps); val oy = FloatArray(steps); val op = FloatArray(steps); val ot = IntArray(steps)
    var seg = 0
    for (k in 0 until steps) {
        val tau = k / (steps - 1).toFloat()
        val d = minimumJerk(tau) * total
        while (seg < n - 2 && cum[seg + 1] < d) seg++
        val span = cum[seg + 1] - cum[seg]
        val f = if (span > 0f) ((d - cum[seg]) / span).coerceIn(0f, 1f) else 0f
        ox[k] = xs[seg] + (xs[seg + 1] - xs[seg]) * f
        oy[k] = ys[seg] + (ys[seg + 1] - ys[seg]) * f
        op[k] = ps[seg] + (ps[seg + 1] - ps[seg]) * f
        ot[k] = startMs + (tau * durationMs).toInt()
    }
    return InkPoints(ox, oy, op, ot)
}

/** Times the strokes of one word (regular strokes grouped per letter, then the delayed dots/bars). */
fun timeWord(strokes: List<LaidStroke>, start: MotionState, profile: SpeedProfile): Pair<List<TimedStroke>, MotionState> {
    var t = start.timeMs; var chars = start.chars
    val out = ArrayList<TimedStroke>()
    fun emit(s: LaidStroke, dur: Int) {
        val bold = s.ps.maxOrNull().let { (it ?: 0f) > 0.95f }
        out += TimedStroke(s.page, sampleStroke(s.xs, s.ys, s.ps, t, dur), bold, s.wordIndex, s.tone)
        t += dur
    }
    val regular = strokes.filter { !it.delayed }
    for (group in regular.groupBy { it.charIndex }.values) {
        val budget = 1000f / cps(profile, chars)
        val lens = group.map { length(it.xs, it.ys) }
        val total = max(lens.sum(), 1e-3f)
        group.forEachIndexed { i, s ->
            emit(s, max(MIN_STROKE_MS, (budget * DRAW_SHARE * lens[i] / total).toInt()))
            if (i < group.lastIndex) t += PEN_LIFT_MS
        }
        t += max(MIN_GAP_MS, (budget * (1f - DRAW_SHARE)).toInt()); chars++
    }
    for (s in strokes.filter { it.delayed }) { t += PEN_LIFT_MS; emit(s, DELAYED_STROKE_MS) }
    t += WORD_PAUSE_MS
    return out to MotionState(t, chars)
}

/** Times a straight-ish ruled line at a fixed pen speed. */
fun timeRule(xs: FloatArray, ys: FloatArray, start: MotionState, page: Int, wordIndex: Int): Pair<TimedStroke, MotionState> {
    val ps = FloatArray(xs.size) { 0.6f }
    val dur = max(120, (length(xs, ys) / RULE_SPEED).toInt())
    val stroke = TimedStroke(page, sampleStroke(xs, ys, ps, start.timeMs, dur), false, wordIndex)
    return stroke to start.copy(timeMs = start.timeMs + dur + WORD_PAUSE_MS)
}

fun blockPause(state: MotionState) = state.copy(timeMs = state.timeMs + BLOCK_PAUSE_MS)

/** How many points of [stroke] have been reached by time [tMs] (binary search on the timestamps). */
fun pointsReachedBy(stroke: TimedStroke, tMs: Int): Int {
    val ts = stroke.points.ts
    var lo = 0; var hi = ts.size
    while (lo < hi) { val mid = (lo + hi) ushr 1; if (ts[mid] <= tMs) lo = mid + 1 else hi = mid }
    return lo
}

/** Splits [stroke] at time [tMs]: the part already written (may be empty). Used when the pen interrupts the hand. */
fun truncateAt(stroke: TimedStroke, tMs: Int): TimedStroke? {
    val n = min(pointsReachedBy(stroke, tMs), stroke.points.size)
    return if (n < 1) null else TimedStroke(stroke.page, stroke.points.slice(0, n), stroke.bold, stroke.wordIndex, stroke.tone)
}
