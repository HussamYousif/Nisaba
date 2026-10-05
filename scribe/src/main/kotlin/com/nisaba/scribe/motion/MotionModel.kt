package com.nisaba.scribe.motion

import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.shared.PageRect
import com.nisaba.scribe.layout.LayoutCursor

/** A pen stroke with timing: `points.ts` are milliseconds since the start of the whole answer. */
class TimedStroke(
    val page: Int,
    val points: InkPoints,
    val bold: Boolean,
    val wordIndex: Int,
    val tone: com.nisaba.scribe.layout.InkTone = com.nisaba.scribe.layout.InkTone.Plain,
) {
    val startMs: Int get() = points.ts.first()
    val endMs: Int get() = points.ts.last()
}

/** Characters-per-second ramp: starts quick so short answers feel written, then speeds up so long ones don't drag. */
data class SpeedProfile(val startCps: Float, val endCps: Float, val rampStartChars: Int = 150, val rampLengthChars: Int = 150)

data class MotionState(val timeMs: Int = 0, val chars: Int = 0)

data class ScribePlanState(val cursor: LayoutCursor, val motion: MotionState = MotionState(), val blocks: Int = 0)

/** What planning one block produced. */
class PlanStep(val strokes: List<TimedStroke>, val state: ScribePlanState, val bounds: PageRect?)
