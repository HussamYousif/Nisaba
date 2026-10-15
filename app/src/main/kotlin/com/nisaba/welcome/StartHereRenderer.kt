package com.nisaba.welcome

import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.ink.InkColor
import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.ink.StrokeOrigin
import com.nisaba.domain.settings.HandSpeed
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.IdGen
import com.nisaba.domain.shared.PageSize
import com.nisaba.domain.shared.StrokeId
import com.nisaba.scribe.hand.GlyphSet
import com.nisaba.scribe.hand.HumanParams
import com.nisaba.scribe.layout.LayoutRegion
import com.nisaba.scribe.layout.OccupancyLookup
import com.nisaba.scribe.motion.ScribePlanUseCase
import com.nisaba.scribe.motion.speedProfile

/** The first page of the "Start here" notebook, written by the hand itself (no model, no network). */
class StartHereRenderer(private val hand: GlyphSet, private val ids: IdGen) {
    fun strokes(page: PageSize = PageSize.PORTRAIT): List<Stroke> {
        val region = LayoutRegion.below(page, LayoutRegion.MARGIN, LayoutRegion.MARGIN_TOP, X_HEIGHT)
        val planner = ScribePlanUseCase(hand, region, OccupancyLookup { null }, SEED, HumanParams.CASUAL, speedProfile(HandSpeed.Swift))
        var state = planner.start()
        val exchange = ExchangeId(EXCHANGE)
        return BLOCKS.flatMap { block -> planner(state, block).also { state = it.state }.strokes }.filter { it.page == 0 }.map { t ->
            val p = t.points
            val t0 = p.ts.first()
            val points = InkPoints(p.xs, p.ys, p.ps, IntArray(p.size) { p.ts[it] - t0 })
            Stroke(StrokeId(ids.next()), points, WIDTH, InkColor.LAPIS, StrokeOrigin.Oracle(exchange))
        }
    }

    companion object {
        const val NAME = "Start here"
        const val EXCHANGE = "start-here"
        private const val X_HEIGHT = 30f
        private const val WIDTH = 3.5f
        private const val SEED = 7L

        private fun item(text: String) = Block.ListItem(false, "-", 0, listOf(Inline.Text(text)))

        private val BLOCKS = listOf(
            Block.Heading(1, listOf(Inline.Text("Start here"))),
            item("Write anything, then pause: Nisaba answers beneath it."),
            item("Swipe sideways to turn the page; a new one waits at the end."),
            item("Tap with two fingers to undo, three to redo."),
            item("Hold a finger on an answer: erase it, ask again, another voice."),
            item("The dashed loop picks ink: move it, check it, ask about it."),
            item("Circle or underline anything and write \"?\" to ask about it."),
            item("Tap the top edge or the star for the toolbar; hold the star for a few tools."),
            item("The three dots at the top right: check my work, quiz me, today's page, paper, the voice."),
            Block.Paragraph(listOf(Inline.Text("Try it now: write a question below and pause."))),
        )
    }
}
