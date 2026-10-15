package com.nisaba.domain.conversation

import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.NetworkKind
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.PageSize
import com.nisaba.domain.shared.StrokeId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConversationRulesTest {
    private val page = PageId("p")
    private val cfg = ConvSettings(pauseMillis = 3500, listening = true)
    private val block = Block.Paragraph(listOf(Inline.Text("hi")))
    private fun up(id: String, at: Long, r: PageRect = PageRect(100f, 200f, 300f, 260f)) = ConvEvent.PenUp(StrokeId(id), r, page, at)

    /** Feeds [events] one by one; returns the final status and every effect emitted, in order. */
    private class Run(var status: ConvStatus = ConvStatus(), val settings: ConvSettings) {
        val effects = mutableListOf<ConvEffect>()
        fun send(e: ConvEvent): Run { val (s, fx) = reduce(status, e, settings); status = s; effects += fx; return this }
        val state get() = status.state
        inline fun <reified T : ConvEffect> effect() = effects.filterIsInstance<T>()
    }

    private fun run(settings: ConvSettings = cfg) = Run(settings = settings)

    private fun toThinking(r: Run = run()): Run = r.send(ConvEvent.PenDown).send(up("a", 1000)).send(ConvEvent.Tick(4500))

    @Test fun writingThenPauseStartsTheTimerAndThenTheRequest() {
        val r = run().send(ConvEvent.PenDown)
        assertEquals(ConvState.Writing(null), r.state)
        r.send(up("a", 1000))
        assertTrue(r.state is ConvState.Pausing)
        assertEquals(listOf(ConvEffect.StartTimer(4500)), r.effect<ConvEffect.StartTimer>())
        r.send(ConvEvent.Tick(4500))
        val t = r.state as ConvState.Thinking
        assertEquals(1, t.reqId)
        assertEquals(1, r.effect<ConvEffect.SendRequest>().size)
        assertEquals(1, r.effect<ConvEffect.ShowInkwell>().size)
    }

    @Test fun earlyOrStaleTicksDoNothing() {
        val r = run().send(ConvEvent.PenDown).send(up("a", 1000)).send(ConvEvent.Tick(4000))
        assertTrue(r.state is ConvState.Pausing)
        assertTrue(r.effect<ConvEffect.SendRequest>().isEmpty())
    }

    @Test fun writingMoreWithinThePauseMakesOneUtteranceAndOneRequest() {
        val r = run().send(ConvEvent.PenDown).send(up("a", 1000, PageRect(100f, 200f, 300f, 260f)))
            .send(ConvEvent.PenDown).send(up("b", 3000, PageRect(320f, 210f, 500f, 300f)))
            .send(ConvEvent.Tick(4500))                                    // first deadline passed, but pen wrote again
        assertTrue(r.state is ConvState.Pausing)                            // deadline moved to 6500
        assertTrue(r.effect<ConvEffect.SendRequest>().isEmpty())
        r.send(ConvEvent.Tick(6500))
        val send = r.effect<ConvEffect.SendRequest>().single()
        assertEquals(setOf(StrokeId("a"), StrokeId("b")), send.utt.strokeIds)
        assertEquals(PageRect(100f, 200f, 500f, 300f), send.utt.bounds)
    }

    @Test fun penDownWhilePausingCancelsTheTimer() {
        val r = run().send(ConvEvent.PenDown).send(up("a", 1000)).send(ConvEvent.PenDown)
        assertTrue(ConvEffect.CancelTimer in r.effects)
        assertEquals(setOf(StrokeId("a")), (r.state as ConvState.Writing).utt!!.strokeIds)
    }

    @Test fun penDownWhileThinkingCancelsTheRequestAndKeepsTheUtterance() {
        val r = toThinking().send(ConvEvent.PenDown)
        assertEquals(ConvEffect.CancelRequest(1), r.effect<ConvEffect.CancelRequest>().single())
        assertEquals(ConvEffect.HideInkwell(dry = true), r.effect<ConvEffect.HideInkwell>().single())
        assertTrue(r.effect<ConvEffect.Record>().isEmpty())
        r.send(up("b", 6000)).send(ConvEvent.Tick(9500))
        val second = r.effect<ConvEffect.SendRequest>().last()
        assertEquals(2, second.reqId)
        assertEquals(setOf(StrokeId("a"), StrokeId("b")), second.utt.strokeIds)
    }

    @Test fun staleEventsFromACancelledRequestAreIgnored() {
        val r = toThinking().send(ConvEvent.PenDown)
        val before = r.effects.size
        r.send(ConvEvent.OracleBlock(1, block)).send(ConvEvent.OracleSilent(1)).send(ConvEvent.OracleDone(1))
            .send(ConvEvent.OracleFailed(1, DomainError.Auth))
        assertEquals(before, r.effects.size)
        assertTrue(r.state is ConvState.Writing)
    }

    @Test fun silentReturnsToIdleAndRecordsSilence() {
        val r = toThinking().send(ConvEvent.OracleSilent(1))
        assertEquals(ConvState.Idle, r.state)
        assertEquals(ConvEffect.Record(1, ExchangeStatus.Silent), r.effect<ConvEffect.Record>().single())
    }

    @Test fun firstBlockHandsInkwellOverToTheHand() {
        val r = toThinking().send(ConvEvent.OracleBlock(1, block))
        assertEquals(ConvState.Scribing(1, blocks = 1), r.state)
        assertEquals(ConvEffect.HideInkwell(dry = false), r.effect<ConvEffect.HideInkwell>().last())
        assertEquals(ConvEffect.PlanBlock(1, block), r.effect<ConvEffect.PlanBlock>().single())
    }

    @Test fun completionNeedsBothTheStreamAndTheHandToFinish() {
        val r = toThinking().send(ConvEvent.OracleBlock(1, block))
        r.send(ConvEvent.OracleDone(1)); assertEquals(ConvState.Scribing(1, streamDone = true, blocks = 1), r.state)
        r.send(ConvEvent.ScribeFinished(1, 1))
        assertEquals(ConvState.Idle, r.state)
        assertEquals(ConvEffect.Record(1, ExchangeStatus.Complete), r.effect<ConvEffect.Record>().single())
    }

    @Test fun aLateBlockReopensAnAlreadyFinishedHand() {
        val r = toThinking().send(ConvEvent.OracleBlock(1, block)).send(ConvEvent.ScribeFinished(1, 1))
        assertEquals(ConvState.Scribing(1, streamDone = false, scribeDone = true, blocks = 1), r.state)
        r.send(ConvEvent.OracleBlock(1, block))
        assertEquals(ConvState.Scribing(1, streamDone = false, scribeDone = false, blocks = 2), r.state)
        assertEquals(2, r.effect<ConvEffect.PlanBlock>().size)
    }

    @Test fun aLateFinishForAnEarlierBlockDoesNotEndTheAnswer() {
        val r = toThinking().send(ConvEvent.OracleBlock(1, block)).send(ConvEvent.OracleBlock(1, block))
        r.send(ConvEvent.ScribeFinished(1, 1)).send(ConvEvent.OracleDone(1))   // block 1's report arrives after block 2
        assertEquals(ConvState.Scribing(1, streamDone = true, blocks = 2), r.state)
        assertTrue(r.effect<ConvEffect.Record>().isEmpty())
        r.send(ConvEvent.ScribeFinished(0, 2))                                   // another request's report
        assertTrue(r.state is ConvState.Scribing)
        r.send(ConvEvent.ScribeFinished(1, 2))
        assertEquals(ConvState.Idle, r.state)
        assertEquals(ExchangeStatus.Complete, r.effect<ConvEffect.Record>().single().status)
    }

    @Test fun penDownWhileScribingStopsTheHandButNotTheReply() {
        val r = toThinking().send(ConvEvent.OracleBlock(1, block)).send(ConvEvent.PenDown)
        assertEquals(ConvState.Interrupted(1, blocks = 1, streamOpen = true), r.state)
        assertEquals(ConvEffect.StopScribe(1), r.effect<ConvEffect.StopScribe>().single())
        assertTrue(r.effect<ConvEffect.CancelRequest>().isEmpty()) // its <heard> and memory still arrive
        assertEquals(ExchangeStatus.Interrupted, r.effect<ConvEffect.Record>().single().status)
        r.send(ConvEvent.OracleBlock(1, block))                     // the rest keeps coming: planned for Continue
        assertEquals(ConvState.Interrupted(1, blocks = 2, streamOpen = true), r.state)
        assertEquals(2, r.effect<ConvEffect.PlanBlock>().size)
        r.send(ConvEvent.TapContinue)
        assertEquals(ConvState.Scribing(1, streamDone = false, blocks = 2), r.state)
    }

    @Test fun aDotIsNotWorthARequest() {
        val r = run().send(ConvEvent.PenDown).send(up("dot", 1000, PageRect(500f, 500f, 504f, 503f))).send(ConvEvent.Tick(4500))
        assertEquals(ConvState.Idle, r.state)
        assertTrue(r.effect<ConvEffect.SendRequest>().isEmpty())
    }

    @Test fun erasingTheQuestionAwayAsksNothingAndErasingPartOfItShrinksIt() {
        val whole = run().send(ConvEvent.PenDown).send(up("a", 1000)).send(ConvEvent.PenDown)
            .send(ConvEvent.Erased(emptySet(), null)).send(ConvEvent.PenAborted(1200)).send(ConvEvent.Tick(9000))
        assertEquals(ConvState.Idle, whole.state); assertTrue(whole.effect<ConvEffect.SendRequest>().isEmpty())
        val part = run().send(ConvEvent.PenDown).send(up("a", 1000)).send(ConvEvent.PenDown).send(up("b", 1500)).send(ConvEvent.PenDown)
            .send(ConvEvent.Erased(setOf(StrokeId("a")), PageRect(100f, 200f, 150f, 260f))).send(ConvEvent.PenAborted(1600))
        assertEquals(setOf(StrokeId("a")), (part.state as ConvState.Pausing).utt.strokeIds)
        assertEquals(PageRect(100f, 200f, 150f, 260f), (part.state as ConvState.Pausing).utt.bounds)
    }

    @Test fun interruptionAfterTheStreamEndedDoesNotCancelAnything() {
        val r = toThinking().send(ConvEvent.OracleBlock(1, block)).send(ConvEvent.OracleDone(1)).send(ConvEvent.PenDown)
        assertTrue(r.effect<ConvEffect.CancelRequest>().isEmpty())
        assertEquals(ConvState.Interrupted(1, blocks = 1, streamOpen = false), r.state)
    }

    @Test fun tapContinueResumesAndWritingAfterInterruptionStartsAFreshUtterance() {
        val r = toThinking().send(ConvEvent.OracleBlock(1, block)).send(ConvEvent.PenDown)
        r.send(ConvEvent.TapContinue)
        assertEquals(ConvEffect.ResumeScribe(1), r.effect<ConvEffect.ResumeScribe>().single())
        assertEquals(ConvState.Scribing(1, streamDone = false, blocks = 1), r.state) // the reply was still arriving
        r.send(ConvEvent.ScribeFinished(1, 1)).send(ConvEvent.OracleDone(1))
        assertEquals(ConvState.Idle, r.state)
        val r2 = toThinking().send(ConvEvent.OracleBlock(1, block)).send(ConvEvent.PenDown).send(up("z", 9000))
        assertEquals(setOf(StrokeId("z")), (r2.state as ConvState.Pausing).utt.strokeIds)
    }

    @Test fun failureShowsMessageAndRetryResendsTheSameUtterance() {
        val err = DomainError.Network(NetworkKind.Offline)
        val r = toThinking().send(ConvEvent.OracleFailed(1, err))
        assertEquals(ConvEffect.ShowFailure(err), r.effect<ConvEffect.ShowFailure>().single())
        assertEquals(ExchangeStatus.Failed, r.effect<ConvEffect.Record>().single().status)
        val utt = (r.state as ConvState.Failed).utt
        r.send(ConvEvent.TapRetry)
        val retry = r.effect<ConvEffect.SendRequest>().last()
        assertEquals(2, retry.reqId); assertEquals(utt, retry.utt)
        assertTrue(ConvEffect.ClearFailure in r.effects)
    }

    @Test fun penDownAfterFailureKeepsTheUtteranceAndClearsTheMessage() {
        val r = toThinking().send(ConvEvent.OracleFailed(1, DomainError.Auth)).send(ConvEvent.PenDown)
        assertTrue(ConvEffect.ClearFailure in r.effects)
        assertEquals(setOf(StrokeId("a")), (r.state as ConvState.Writing).utt!!.strokeIds)
    }

    @Test fun anEmptyAnswerCountsAsAFailure() {
        val r = toThinking().send(ConvEvent.OracleDone(1))
        assertTrue(r.state is ConvState.Failed)
    }

    @Test fun failureAfterTheAnswerStartedKeepsTheInkWrittenSoFar() {
        val r = toThinking().send(ConvEvent.OracleBlock(1, block)).send(ConvEvent.OracleFailed(1, DomainError.Network(NetworkKind.Timeout)))
        assertTrue(r.state is ConvState.Scribing)                          // keep writing what we have
        r.send(ConvEvent.ScribeFinished(1, 1))
        assertEquals(ConvState.Idle, r.state)
        assertEquals(ExchangeStatus.Interrupted, r.effect<ConvEffect.Record>().single().status)
    }

    @Test fun notListeningHoldsTheUtteranceUntilAskNow() {
        val r = run(ConvSettings(3500, listening = false)).send(ConvEvent.PenDown).send(up("a", 1000)).send(ConvEvent.Tick(4500))
        assertTrue(r.state is ConvState.Held)
        assertTrue(r.effect<ConvEffect.SendRequest>().isEmpty())
        r.send(ConvEvent.AskNow)
        assertTrue(r.state is ConvState.Thinking)
        assertEquals(1, r.effect<ConvEffect.SendRequest>().size)
    }

    @Test fun askNowDuringThePauseSkipsTheWait() {
        val r = run().send(ConvEvent.PenDown).send(up("a", 1000)).send(ConvEvent.AskNow)
        assertTrue(r.state is ConvState.Thinking)
        assertTrue(ConvEffect.CancelTimer in r.effects)
        r.send(ConvEvent.Tick(4500))                                       // the cancelled timer firing anyway is harmless
        assertEquals(1, r.effect<ConvEffect.SendRequest>().size)
    }

    @Test fun aLessonBeginsOnlyFromAQuietPageAndWaitsWhenMuted() {
        val utt = Utterance(page, emptySet(), PageRect(120f, 160f, 120f, 160f))
        val r = run().send(ConvEvent.Begin(utt))
        assertEquals(utt, r.effect<ConvEffect.SendRequest>().single().utt)
        assertTrue(r.state is ConvState.Thinking)
        val busy = run().send(ConvEvent.PenDown).send(ConvEvent.Begin(utt))
        assertEquals(ConvState.Writing(null), busy.state)
        val muted = run(ConvSettings(3500, listening = false)).send(ConvEvent.Begin(utt))
        assertEquals(ConvState.Held(utt), muted.state)
        assertEquals(1, muted.send(ConvEvent.AskNow).effect<ConvEffect.SendRequest>().size)
    }

    @Test fun askingAgainGoesEvenWhenMutedAndAfterAStoppedAnswer() {
        val utt = Utterance(page, emptySet(), PageRect(100f, 200f, 300f, 260f), Purpose.Again("q", "a"))
        val muted = run(ConvSettings(3500, listening = false)).send(ConvEvent.Begin(utt))
        assertEquals(1, muted.effect<ConvEffect.SendRequest>().size)
        val stopped = toThinking().send(ConvEvent.OracleBlock(1, block)).send(ConvEvent.PenDown).send(ConvEvent.Begin(utt))
        assertEquals(2, stopped.effect<ConvEffect.SendRequest>().last().reqId)
    }

    @Test fun askNowWithNothingWrittenDoesNothing() {
        val r = run().send(ConvEvent.AskNow)
        assertEquals(ConvState.Idle, r.state)
    }

    @Test fun aLiftWithoutAStrokeResumesThePauseOrGoesIdle() {
        val r = run().send(ConvEvent.PenDown).send(up("a", 1000)).send(ConvEvent.PenDown).send(ConvEvent.PenAborted(2000))
        val p = r.state as ConvState.Pausing
        assertEquals(5500L, p.deadlineMillis)
        assertEquals(ConvEffect.StartTimer(5500), r.effect<ConvEffect.StartTimer>().last())
        assertEquals(ConvState.Idle, run().send(ConvEvent.PenDown).send(ConvEvent.PenAborted(500)).state)
    }

    @Test fun anchorSitsBelowTheUtteranceAndIsClampedToTheMargins() {
        val utt = Utterance(page, setOf(StrokeId("a")), PageRect(10f, 500f, 400f, 640f))
        val a = answerAnchor(utt, PageSize.PORTRAIT)
        assertEquals(PagePoint(ANSWER_MARGIN, 640f + ANSWER_LINE_GAP), a)
        val far = answerAnchor(utt.copy(bounds = PageRect(1800f, 0f, 1850f, 10f)), PageSize.PORTRAIT)
        assertTrue(far.x <= PageSize.PORTRAIT.w - ANSWER_MARGIN - 600f)
    }

    private fun randomEvent(rnd: Random, step: Int, req: Int): ConvEvent = when (rnd.nextInt(13)) {
        0, 1 -> ConvEvent.PenDown
        2, 3 -> up("s$step", step * 1000L + 500)
        4 -> ConvEvent.Tick(step * 1000L + 10_000L)
        5 -> ConvEvent.AskNow
        6 -> ConvEvent.OracleSilent(req)
        7, 8 -> ConvEvent.OracleBlock(req, block)
        9 -> ConvEvent.OracleDone(req)
        10 -> ConvEvent.OracleFailed(req, DomainError.Auth)
        11 -> ConvEvent.ScribeFinished(req, rnd.nextInt(0, 4))
        else -> if (rnd.nextBoolean()) ConvEvent.TapContinue else ConvEvent.TapRetry
    }

    /** Random walks: request ids never repeat, and a request is never issued while another is still live. */
    @Test fun randomWalkKeepsAtMostOneLiveRequestAndIncreasingIds() {
        val rnd = Random(11)
        repeat(300) {
            val r = run()
            var live: Int? = null
            val seenIds = mutableListOf<Int>()
            repeat(60) { step ->
                val req = (r.status.lastReqId - rnd.nextInt(0, 2)).coerceAtLeast(0)
                val event = randomEvent(rnd, step, req)
                val (next, fx) = reduce(r.status, event, cfg)
                for (e in fx) when (e) {
                    is ConvEffect.CancelRequest -> if (live == e.reqId) live = null
                    is ConvEffect.SendRequest -> {
                        assertEquals(null, live, "request ${e.reqId} issued while $live still live (event=$event)")
                        live = e.reqId; seenIds += e.reqId
                    }
                    else -> Unit
                }
                if (next.state !is ConvState.Thinking && next.state !is ConvState.Scribing) live = null
                if (next.state is ConvState.Scribing && (next.state as ConvState.Scribing).streamDone) live = null
                r.status = next
            }
            assertEquals(seenIds.sorted(), seenIds, "ids increase")
            assertEquals(seenIds.size, seenIds.toSet().size)
            assertNotNull(r.status)
        }
    }
}
