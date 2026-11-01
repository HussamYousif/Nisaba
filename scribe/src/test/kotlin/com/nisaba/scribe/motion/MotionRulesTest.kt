package com.nisaba.scribe.motion

import com.nisaba.domain.settings.HandSpeed
import com.nisaba.scribe.hand.HandFixtures
import com.nisaba.scribe.hand.HumanParams
import com.nisaba.scribe.hand.LaidStroke
import com.nisaba.scribe.hand.wordStrokes
import com.nisaba.scribe.layout.LaidWord
import com.nisaba.scribe.layout.PlacedChar
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionRulesTest {
    private val hand = HandFixtures.hand()
    private val natural = speedProfile(HandSpeed.Natural)

    private fun word(text: String, index: Int = 0): LaidWord {
        var x = 0f
        val chars = text.map { c -> val g = hand.resolve(c)!!; PlacedChar(c, g, x, 1f).also { x += g.adv * 44f } }
        return LaidWord(0, 100f, 400f, 44f, chars, x, false, 0, index)
    }

    private fun strokes(text: String) = wordStrokes(word(text), 44f, 1, HumanParams.PERFECT)

    @Test fun minimumJerkIsSmoothAndMonotonic() {
        assertEquals(0f, minimumJerk(0f)); assertEquals(1f, minimumJerk(1f), 1e-6f); assertEquals(0.5f, minimumJerk(0.5f), 1e-6f)
        assertTrue((1..100).all { minimumJerk(it / 100f) >= minimumJerk((it - 1) / 100f) })
        assertTrue(minimumJerk(0.05f) < 0.05f * 0.1f, "slow start")
    }

    @Test fun speedRampsFromQuickToSwiftAndIsCapped() {
        assertEquals(20f, cps(natural, 0)); assertEquals(45f, cps(natural, 10_000))
        assertTrue(cps(natural, 100) in 20f..45f)
        assertTrue(cps(speedProfile(HandSpeed.Swift), 0) > cps(natural, 0) && cps(natural, 0) > cps(speedProfile(HandSpeed.Slow), 0))
    }

    @Test fun sampledStrokeKeepsEndpointsAndTimestampsNeverGoBackwards() {
        val xs = floatArrayOf(0f, 100f, 100f); val ys = floatArrayOf(0f, 0f, 50f); val ps = floatArrayOf(.2f, .8f, .4f)
        val p = sampleStroke(xs, ys, ps, startMs = 1000, durationMs = 200)
        assertEquals(0f, p.xs.first(), 0.01f); assertEquals(100f, p.xs.last(), 0.01f); assertEquals(50f, p.ys.last(), 0.01f)
        assertEquals(1000, p.ts.first()); assertEquals(1200, p.ts.last())
        assertTrue(p.ts.toList().zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun fastStrokesStillKeepCurvesSmooth() {
        val s = strokes("e").first()
        val p = sampleStroke(s.xs, s.ys, s.ps, 0, 35)                  // the shortest possible stroke time
        val maxGap = (1 until p.size).maxOf { hypot(p.xs[it] - p.xs[it - 1], p.ys[it] - p.ys[it - 1]) }
        assertTrue(maxGap <= 6f, "gap $maxGap px would draw a polygon instead of a curve")
    }

    @Test fun wordTimingIsMonotonicAndDelayedStrokesComeLast() {
        val (timed, state) = timeWord(strokes("it"), MotionState(), natural)
        assertTrue(timed.zipWithNext().all { (a, b) -> b.startMs >= a.endMs }, "strokes never overlap in time")
        assertEquals(2, state.chars)
        assertTrue(state.timeMs > timed.last().endMs, "word pause after the last stroke")
        assertEquals(strokes("it").last().delayed, true)
        assertTrue(timed.last().points.ys.average() < timed.first().points.ys.average(), "the i-dot (highest) is written last")
    }

    @Test fun aHundredCharactersTakeAboutFiveToTwelveSeconds() {
        var state = MotionState()
        var idx = 0
        repeat(20) { val (_, s) = timeWord(strokes("hello"), state, natural); state = s; idx++ }
        assertEquals(100, state.chars)
        assertTrue(state.timeMs in 4_000..12_000, "took ${state.timeMs} ms")
    }

    @Test fun theSpeedSettingReallyChangesThePace() { // fixed floors once held every setting to ~7 letters/s
        fun hundred(speed: HandSpeed): Int {
            var state = MotionState()
            repeat(20) { state = timeWord(strokes("hello"), state, speedProfile(speed)).second }
            return state.timeMs
        }
        val (slow, natural, swift) = listOf(HandSpeed.Slow, HandSpeed.Natural, HandSpeed.Swift).map(::hundred)
        assertTrue(slow > natural * 1.3 && natural > swift * 1.2, "slow $slow, natural $natural, swift $swift ms")
        assertTrue(natural < 8_000, "a hundred letters at the natural pace took $natural ms")
    }

    @Test fun laterWordsAreWrittenFasterThanEarlyOnes() {
        val (_, a) = timeWord(strokes("hello"), MotionState(), natural)
        val (_, b) = timeWord(strokes("hello"), MotionState(0, 400), natural)
        assertTrue(b.timeMs < a.timeMs)
    }

    @Test fun truncatingAStrokeKeepsWhatWasAlreadyWritten() {
        val (timed, _) = timeWord(strokes("l"), MotionState(), natural)
        val s = timed[0]
        val mid = (s.startMs + s.endMs) / 2
        val part = truncateAt(s, mid)!!
        assertTrue(part.points.size in 1 until s.points.size)
        assertTrue(part.endMs <= mid)
        assertEquals(null, truncateAt(s, s.startMs - 1))
        assertEquals(s.points.size, truncateAt(s, s.endMs + 100)!!.points.size)
        assertEquals(pointsReachedBy(s, mid), part.points.size)
    }

    @Test fun rulesAreTimedAtAFixedSpeed() {
        val (s, next) = timeRule(floatArrayOf(0f, 700f), floatArrayOf(0f, 0f), MotionState(500, 3), 0, -1)
        assertEquals(500, s.startMs)
        assertEquals(500 + 500, s.endMs)                              // 700 px / 1.4 px per ms
        assertTrue(next.timeMs > s.endMs)
        assertEquals(3, next.chars)
    }

    @Test fun strokeListsAreNotMutated() {
        val original = strokes("ab")
        val copy = original.map {
            LaidStroke(it.page, it.xs.copyOf(), it.ys.copyOf(), it.ps.copyOf(), it.delayed, it.wordIndex, it.charIndex)
        }
        timeWord(original, MotionState(), natural)
        assertEquals(copy.map { it.xs.toList() }, original.map { it.xs.toList() })
    }
}
