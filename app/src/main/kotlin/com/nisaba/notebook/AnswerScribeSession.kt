package com.nisaba.notebook

import com.nisaba.domain.answer.Block
import com.nisaba.domain.conversation.ConversationSurface
import com.nisaba.domain.conversation.Utterance
import com.nisaba.domain.conversation.answerAnchor
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.ink.estimateXHeight
import com.nisaba.domain.settings.HandStyle
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.PageSize
import com.nisaba.domain.shared.inflate
import com.nisaba.scribe.hand.GlyphSet
import com.nisaba.scribe.hand.HumanParams
import com.nisaba.scribe.layout.LayoutRegion
import com.nisaba.scribe.layout.Occupancy
import com.nisaba.scribe.layout.OccupancyLookup
import com.nisaba.scribe.motion.ScribePlanState
import com.nisaba.scribe.motion.ScribePlanUseCase
import com.nisaba.scribe.motion.TimedStroke
import com.nisaba.scribe.motion.speedProfile

/** Instructions from the ViewModel to the view layer that draws the hand. */
sealed interface ScribeCommand {
    data class Play(val strokes: List<TimedStroke>) : ScribeCommand
    data object Stop : ScribeCommand
    data object Resume : ScribeCommand
    data object Reset : ScribeCommand

    /** The page turned for a spilling answer: continue writing on [page] (0 = the question's page). */
    data class PageReady(val page: Int) : ScribeCommand
    data class Inkwell(val at: PagePoint?) : ScribeCommand
}

/**
 * The conversation's window onto the page: turns "an answer block arrived" into planned pen strokes near the question
 * and tells the view what to show. One instance per open page.
 */
class AnswerScribeSession(
    private val hands: (HandStyle) -> GlyphSet,
    private val settings: () -> Settings,
    private val pageSize: () -> PageSize,
    private val ink: () -> List<Stroke>,
    private val printed: () -> Occupancy?,
    private val emit: (ScribeCommand) -> Unit,
    private val onFailure: (DomainError?) -> Unit,
) : ConversationSurface {
    private var utt: Utterance? = null
    private var anchor: PagePoint? = null
    private var planner: ScribePlanUseCase? = null
    private var planState: ScribePlanState? = null

    /** The request whose answer is being written (used to group strokes into one exchange). */
    var currentReq: Int = 0
        private set

    override fun showInkwell(utt: Utterance) {
        this.utt = utt
        anchor = answerAnchor(utt, pageSize()).also { emit(ScribeCommand.Inkwell(it)) }
    }

    override fun hideInkwell(dry: Boolean) = emit(ScribeCommand.Inkwell(null))

    override fun planBlock(reqId: Int, block: Block) {
        if (reqId != currentReq || planner == null) startAnswer(reqId)
        val p = planner ?: return
        val step = p(planState ?: p.start(), block)
        planState = step.state
        if (step.strokes.isNotEmpty()) emit(ScribeCommand.Play(step.strokes)) // strokes carry their page; the animator pauses at each break
    }

    private fun startAnswer(reqId: Int) {
        currentReq = reqId
        val page = pageSize()
        val s = settings()
        val u = utt
        val a = anchor ?: PagePoint(LayoutRegion.MARGIN, LayoutRegion.MARGIN_TOP)
        val userInk = ink().filter { u == null || it.id in u.strokeIds }
        val xHeight = estimateXHeight(userInk)
        val region = LayoutRegion.below(page, a.x, a.y, xHeight)
        val occupancy = (printed()?.copy() ?: emptyOccupancy(page, region))
            .also { o -> ink().forEach { s -> o.mark(s.bounds.inflate(INK_PAD)) } }
        planner = ScribePlanUseCase(
            hands(s.handStyle), region, OccupancyLookup { if (it == 0) occupancy else null }, seedFor(reqId, u),
            if (s.handStyle == HandStyle.Formal) HumanParams.FORMAL else HumanParams.CASUAL, speedProfile(s.handSpeed),
        )
        planState = planner!!.start()
    }

    /** A whole-page grid, or on an endless canvas just a window around the answer's column (the canvas is far too big). */
    private fun emptyOccupancy(page: PageSize, region: LayoutRegion) =
        if (page.isEndless) {
            Occupancy.window(PageRect(region.left - WINDOW_PAD, region.top - WINDOW_PAD, region.right + WINDOW_PAD, region.bottom))
        } else {
            Occupancy.empty(page)
        }

    private companion object {
        const val INK_PAD = 12f
        const val WINDOW_PAD = 400f
    }

    private fun seedFor(reqId: Int, utt: Utterance?): Long = (utt?.strokeIds?.hashCode() ?: 0).toLong() * 31 + reqId

    override fun stopScribe(reqId: Int) = emit(ScribeCommand.Stop)
    override fun resumeScribe(reqId: Int) = emit(ScribeCommand.Resume)
    override fun showFailure(error: DomainError) = onFailure(error)
    override fun clearFailure() = onFailure(null)
}
