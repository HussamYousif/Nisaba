package com.nisaba.notebook

import com.nisaba.canvas.BlockRef
import com.nisaba.domain.answer.Block
import com.nisaba.domain.conversation.ConversationSurface
import com.nisaba.domain.conversation.Utterance
import com.nisaba.domain.conversation.answerAnchor
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.ink.StrokeOrigin
import com.nisaba.domain.ink.estimateXHeight
import com.nisaba.domain.persona.Persona
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.persona.handFor
import com.nisaba.domain.settings.HandStyle
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.PageSize
import com.nisaba.domain.shared.inflate
import com.nisaba.domain.shared.intersects
import com.nisaba.scribe.hand.GlyphSet
import com.nisaba.scribe.hand.HumanParams
import com.nisaba.scribe.layout.LayoutRegion
import com.nisaba.scribe.layout.Occupancy
import com.nisaba.scribe.layout.OccupancyLookup
import com.nisaba.scribe.motion.ScribePlanState
import com.nisaba.scribe.motion.ScribePlanUseCase
import com.nisaba.scribe.motion.TimedStroke
import com.nisaba.scribe.motion.speedProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Instructions from the ViewModel to the view layer that draws the hand. */
sealed interface ScribeCommand {
    /** [strokes] of block [ref], to be written starting on [pageId] (may be empty: the block still counts as written). */
    data class Play(val strokes: List<TimedStroke>, val ref: BlockRef, val pageId: PageId) : ScribeCommand
    data object Stop : ScribeCommand
    data object Resume : ScribeCommand
    data object Reset : ScribeCommand

    /** The page turned for a spilling answer: continue writing on [page] (0 = the question's page), which is [pageId]. */
    data class PageReady(val page: Int, val pageId: PageId) : ScribeCommand
    /** The model is thinking; its answer will start at [at] (nothing is drawn until the hand writes). */
    data class AnswerAt(val at: PagePoint) : ScribeCommand
}

/** What the hand reports besides failures: a memory note, a cut-short answer, a block it couldn't lay out. */
class ScribeNotices(
    val onMemoryNote: (String) -> Unit = {},
    val onCutShort: () -> Unit = {},
    val onLayoutError: (Throwable) -> Unit = { android.util.Log.w("Nisaba/Scribe", "block skipped", it) },
)

/**
 * The conversation's window onto the page: turns "an answer block arrived" into planned pen strokes near the question
 * and tells the view what to show. One instance per open page ([session] tells instances apart); after [close] it is mute.
 * A long answer spills onto the next page and the instance carries on there, so an answer is written on its question's
 * page ([pageSize], [ink] and [printed] describe the page in front of the person), not necessarily on [pageId].
 */
class AnswerScribeSession(
    private val session: Int,
    private val pageId: PageId,
    private val hands: (HandStyle) -> GlyphSet,
    private val settings: () -> Settings,
    private val pageSize: () -> PageSize,
    private val ink: () -> List<Stroke>,
    private val printed: () -> Occupancy?,
    private val emit: (ScribeCommand) -> Unit,
    private val onFailure: (DomainError?) -> Unit,
    private val notices: ScribeNotices = ScribeNotices(),
    /** Any voice by id (each writes in its own hand: Settings → The hand). */
    private val voices: (String) -> Persona = PersonaRegistry::of,
    /** Where the hand's planning runs: one block after another, off the main thread (layout must not stall frames). */
    private val planScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
) : ConversationSurface {
    private var utt: Utterance? = null
    private var anchor: PagePoint? = null

    /** One answer's planner and where it got to; only ever touched inside [planScope] once created. */
    private class PlanRun(val planner: ScribePlanUseCase, val ink: Int?) { var state: ScribePlanState = planner.start() }
    private var run: PlanRun? = null
    private var currentReq = 0
    private var blocks = 0

    /** Where the current answer is written: the page of its question (after a spill that is no longer [pageId]). */
    private var answerPage = pageId
    private var closed = false

    fun close() { closed = true; planScope.cancel() }

    private fun emitIfOpen(cmd: ScribeCommand) { if (!closed) emit(cmd) }

    override fun showInkwell(utt: Utterance) {
        this.utt = utt
        anchor = answerAnchor(utt, pageSize()).also { emitIfOpen(ScribeCommand.AnswerAt(it)) }
    }

    override fun hideInkwell(dry: Boolean) = Unit // nothing was shown

    override fun planBlock(reqId: Int, block: Block, exchangeId: ExchangeId, personaId: String) {
        if (closed) return
        if (reqId != currentReq || run == null) startAnswer(reqId, personaId) // reads the page as it is now, on the caller's thread
        val r = run ?: return
        val ref = BlockRef(session, reqId, ++blocks, exchangeId, r.ink)
        val page = answerPage
        planScope.launch {
            // a block the hand can't lay out is skipped, never fatal: the answer carries on and still finishes
            val strokes = try {
                r.planner(r.state, block).also { r.state = it.state }.strokes
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                notices.onLayoutError(e); emptyList()
            }
            // strokes carry their page; the animator pauses at each break. Sent even when empty so the block counts.
            emitIfOpen(ScribeCommand.Play(strokes, ref, page))
        }
    }

    private fun startAnswer(reqId: Int, personaId: String) {
        currentReq = reqId
        blocks = 0
        val page = pageSize()
        val s = settings()
        val u = utt
        answerPage = u?.pageId ?: pageId
        val a = anchor ?: PagePoint(LayoutRegion.MARGIN, LayoutRegion.MARGIN_TOP)
        val userInk = when {
            u == null -> ink()
            u.strokeIds.isNotEmpty() -> ink().filter { it.id in u.strokeIds }
            else -> ink().filter { it.origin is StrokeOrigin.User && it.bounds.intersects(u.bounds) } // asked again: the old question
        }
        // nothing of theirs to match (a lesson opening, a tidy copy): a calm, compact size
        val xHeight = if (userInk.isEmpty()) OPENING_X_HEIGHT else estimateXHeight(userInk)
        val region = LayoutRegion.below(page, a.x, a.y, xHeight)
        val occupancy = (printed()?.copy() ?: emptyOccupancy(page, region))
            .also { o -> ink().forEach { s -> o.mark(s.bounds.inflate(INK_PAD)) } }
        val hand = voices(personaId).handFor(s) // each voice its own slant, neatness, pace and ink
        val human = (if (s.handStyle == HandStyle.Formal) HumanParams.FORMAL else HumanParams.CASUAL).voiced(hand.slantDeg, hand.tidiness)
        run = PlanRun(
            ScribePlanUseCase(
                hands(s.handStyle), region, OccupancyLookup { if (it == 0) occupancy else null }, seedFor(reqId, u),
                human, speedProfile(hand.speed ?: s.handSpeed),
            ),
            hand.ink,
        )
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
        const val OPENING_X_HEIGHT = 32f
    }

    private fun seedFor(reqId: Int, utt: Utterance?): Long = (utt?.strokeIds?.hashCode() ?: 0).toLong() * 31 + reqId

    override fun stopScribe(reqId: Int) = emitIfOpen(ScribeCommand.Stop)
    override fun resumeScribe(reqId: Int) = emitIfOpen(ScribeCommand.Resume)
    override fun showFailure(error: DomainError) = onFailure(error)
    override fun clearFailure() = onFailure(null)
    override fun showMemoryNote(note: String) = notices.onMemoryNote(note)
    override fun showCutShort() = notices.onCutShort()
}
