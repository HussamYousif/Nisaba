package com.nisaba.scribe.motion

import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.settings.HandSpeed
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.hand.HandFixtures
import com.nisaba.scribe.hand.HumanParams
import com.nisaba.scribe.layout.LayoutRegion
import com.nisaba.scribe.layout.Occupancy
import com.nisaba.scribe.layout.OccupancyLookup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScribePlanUseCaseTest {
    private val page = PageSize.PORTRAIT
    private val region = LayoutRegion.below(page, 120f, 300f, 44f)
    private fun planner(seed: Long = 5, occ: OccupancyLookup = OccupancyLookup { null }) =
        ScribePlanUseCase(HandFixtures.hand(), region, occ, seed, HumanParams.CASUAL, speedProfile(HandSpeed.Natural))

    private fun para(t: String) = Block.Paragraph(listOf(Inline.Text(t)))
    private val blocks = listOf(Block.Heading(1, listOf(Inline.Text("Title"))), para("First paragraph of the answer."), para("Second."))

    private fun run(p: ScribePlanUseCase, bs: List<Block>): Pair<List<TimedStroke>, ScribePlanState> {
        var st = p.start(); val out = ArrayList<TimedStroke>()
        for (b in bs) { val step = p(st, b); out += step.strokes; st = step.state }
        return out to st
    }

    private fun fingerprint(s: List<TimedStroke>) = s.map { it.points.xs.toList() to it.points.ts.toList() }

    @Test fun planningIsDeterministicInTheSeed() {
        assertEquals(fingerprint(run(planner(5), blocks).first), fingerprint(run(planner(5), blocks).first))
        assertTrue(fingerprint(run(planner(5), blocks).first) != fingerprint(run(planner(6), blocks).first))
    }

    @Test fun timeNeverRunsBackwardsAcrossBlocks() {
        val (strokes, state) = run(planner(), blocks)
        assertTrue(strokes.zipWithNext().all { (a, b) -> b.startMs >= a.endMs })
        assertEquals(blocks.size, state.blocks)
        assertTrue(state.motion.timeMs > strokes.last().endMs)
    }

    @Test fun laterBlocksStartAfterAPauseAndSitBelowEarlierOnes() {
        val p = planner(); var st = p.start()
        val a = p(st, blocks[1]); st = a.state
        val b = p(st, blocks[2])
        assertTrue(b.strokes.first().startMs >= a.strokes.last().endMs + 300, "block pause")
        assertTrue(b.bounds!!.top > a.bounds!!.top)
    }

    @Test fun headingUnderlineIsTheLastStrokeOfItsBlock() {
        val step = planner().let { it(it.start(), blocks[0]) }
        val last = step.strokes.last()
        assertTrue(last.wordIndex < 0, "rules use negative word ids")
        assertTrue(last.points.xs.last() - last.points.xs.first() > 100f)
    }

    @Test fun everythingStaysOnThePage() {
        val (strokes, _) = run(planner(), blocks)
        val onFirst = strokes.filter { it.page == 0 }
        assertTrue(onFirst.all { s -> s.points.xs.all { it in 0f..page.w } && s.points.ys.all { it in 0f..page.h } })
    }

    @Test fun answersAvoidExistingInk() {
        val ink = Occupancy.of(page, listOf(PageRect(100f, 290f, 1700f, 700f)))
        val (strokes, _) = run(planner(occ = { p -> if (p == 0) ink else null }), listOf(para("Below the ink")))
        val minY = strokes.minOf { it.points.ys.min() }
        assertTrue(minY > 700f, "writing starts below the blocked area, top=$minY")
    }

    @Test fun aLongAnswerSpillsOntoTheNextPage() {
        val text = "word ".repeat(400)
        val (strokes, _) = run(planner(), listOf(para(text)))
        assertTrue(strokes.any { it.page == 1 })
    }
}
