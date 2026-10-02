package com.nisaba.scribe.motion

import com.nisaba.domain.answer.Block
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.union
import com.nisaba.scribe.hand.GlyphSet
import com.nisaba.scribe.hand.HumanParams
import com.nisaba.scribe.hand.handPath
import com.nisaba.scribe.hand.wordStrokes
import com.nisaba.scribe.layout.LaidBlock
import com.nisaba.scribe.layout.LayoutCursor
import com.nisaba.scribe.layout.LayoutRegion
import com.nisaba.scribe.layout.OccupancyLookup
import com.nisaba.scribe.layout.layoutBlock

/**
 * AnswerBlock → laid out → humanized → timed pen strokes, one block at a time so the hand can start writing while the
 * model is still talking. Stateless: the caller threads [ScribePlanState] through (deterministic in [seed]).
 */
class ScribePlanUseCase(
    private val glyphs: GlyphSet,
    private val region: LayoutRegion,
    private val occupancy: OccupancyLookup,
    private val seed: Long,
    private val human: HumanParams,
    private val speed: SpeedProfile,
) {
    fun start() = ScribePlanState(LayoutCursor(0, region.top))

    operator fun invoke(state: ScribePlanState, block: Block): PlanStep {
        val (laid, cursor) = layoutBlock(block, state.cursor, region, occupancy, glyphs)
        val startMotion = if (state.blocks == 0) state.motion else blockPause(state.motion)
        val (strokes, motion) = write(laid, startMotion, state.blocks)
        val bounds = strokes.map { s -> s.points.bounds() }.reduceOrNull { a, b -> a.union(b) }
        return PlanStep(strokes, ScribePlanState(cursor, motion, state.blocks + 1), bounds ?: laid.bounds)
    }

    /** Times the words and the paths of one block, in the order the block asks for. */
    private fun write(laid: LaidBlock, start: MotionState, blockIndex: Int): Pair<List<TimedStroke>, MotionState> {
        var motion = start
        val out = ArrayList<TimedStroke>()
        fun words() = laid.words.forEach { w ->
            val (timed, next) = timeWord(wordStrokes(w, region.xHeight, seed, human), motion, speed)
            out += timed; motion = next
        }
        fun paths() = laid.paths.forEachIndexed { i, p ->
            val (xs, ys) = handPath(p.xs, p.ys, region.xHeight, seed, blockIndex * PATH_SALT + i, human)
            val (timed, next) = timeRule(xs, ys, motion, p.page, -1 - i)
            out += timed; motion = next
        }
        if (laid.pathsFirst) { paths(); words() } else { words(); paths() }
        return out to motion
    }

    private companion object { const val PATH_SALT = 101 }
}
