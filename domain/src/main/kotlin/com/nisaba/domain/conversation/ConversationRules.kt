package com.nisaba.domain.conversation

import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageSize
import com.nisaba.domain.shared.union

const val ANSWER_LINE_GAP = 60f
const val ANSWER_MARGIN = 120f
private const val MIN_ANSWER_WIDTH = 600f

/** Where the answer starts: one line-gap under the utterance, left-aligned to it (clamped to the margins). */
fun answerAnchor(utt: Utterance, page: PageSize): PagePoint {
    val maxX = (page.w - ANSWER_MARGIN - MIN_ANSWER_WIDTH).coerceAtLeast(ANSWER_MARGIN)
    return PagePoint(utt.bounds.left.coerceIn(ANSWER_MARGIN, maxX), utt.bounds.bottom + ANSWER_LINE_GAP)
}

private fun Utterance?.plus(e: ConvEvent.PenUp): Utterance =
    if (this == null) Utterance(e.pageId, setOf(e.strokeId), e.bounds)
    else copy(strokeIds = strokeIds + e.strokeId, bounds = bounds.union(e.bounds))

private fun ConvStatus.to(state: ConvState, vararg effects: ConvEffect) = this.copy(state = state) to effects.toList()

private fun ConvStatus.same() = this to emptyList<ConvEffect>()

/**
 * Pure transition function of the ask loop (DESIGN §4.4, PLAN M2.3).
 * Invariants: request ids only increase; events tagged with a stale id are ignored; a new request is only issued
 * after the previous one was cancelled or finished; a pen-down never lets an old request produce visible ink.
 */
fun reduce(status: ConvStatus, event: ConvEvent, settings: ConvSettings): Pair<ConvStatus, List<ConvEffect>> {
    val s = status.state
    return when (event) {
        ConvEvent.PenDown -> onPenDown(status, s)
        is ConvEvent.PenUp -> onPenUp(status, s, event, settings)
        is ConvEvent.PenAborted -> onPenAborted(status, s, event, settings)
        is ConvEvent.Erased -> onErased(status, s, event)
        is ConvEvent.Tick -> onTick(status, s, event, settings)
        ConvEvent.AskNow -> onAskNow(status, s)
        is ConvEvent.Begin -> onBegin(status, s, event, settings)
        is ConvEvent.OracleSilent -> onSilent(status, s, event)
        is ConvEvent.OracleBlock -> onBlock(status, s, event)
        is ConvEvent.OracleDone -> onDone(status, s, event)
        is ConvEvent.OracleFailed -> onFailed(status, s, event)
        is ConvEvent.ScribeFinished -> onScribeFinished(status, s, event)
        ConvEvent.TapContinue -> onTapContinue(status, s)
        ConvEvent.TapRetry -> onTapRetry(status, s)
    }
}

private fun onPenDown(st: ConvStatus, s: ConvState) = when (s) {
    ConvState.Idle -> st.to(ConvState.Writing(null))
    is ConvState.Writing -> st.same()
    is ConvState.Pausing -> st.to(ConvState.Writing(s.utt), ConvEffect.CancelTimer)
    is ConvState.Held -> st.to(ConvState.Writing(s.utt))
    is ConvState.Thinking -> // nothing was shown yet: drop the request without a trace; the next pause resends everything
        st.to(ConvState.Writing(s.utt), ConvEffect.CancelRequest(s.reqId), ConvEffect.HideInkwell(dry = true))
    // The hand stops; the reply keeps arriving: its transcription and memory still count, and Continue writes the rest.
    is ConvState.Scribing -> st.copy(state = ConvState.Interrupted(s.reqId, s.blocks, streamOpen = !s.streamDone)) to
        listOf(ConvEffect.StopScribe(s.reqId), ConvEffect.Record(s.reqId, ExchangeStatus.Interrupted))
    is ConvState.Interrupted -> st.same()
    is ConvState.Failed -> st.to(ConvState.Writing(s.utt), ConvEffect.ClearFailure)
}

private fun onPenUp(st: ConvStatus, s: ConvState, e: ConvEvent.PenUp, cfg: ConvSettings): Pair<ConvStatus, List<ConvEffect>> {
    val base: Utterance? = when (s) {
        is ConvState.Writing -> s.utt
        else -> null
    }
    return when (s) {
        is ConvState.Writing, is ConvState.Interrupted, ConvState.Idle -> {
            val utt = base.plus(e)
            val deadline = e.atMillis + cfg.pauseMillis
            st.to(ConvState.Pausing(utt, deadline), ConvEffect.StartTimer(deadline))
        }
        else -> st.same()
    }
}

private fun onPenAborted(st: ConvStatus, s: ConvState, e: ConvEvent.PenAborted, cfg: ConvSettings): Pair<ConvStatus, List<ConvEffect>> =
    when (s) {
        is ConvState.Writing ->
            if (s.utt == null) st.to(ConvState.Idle)
            else {
                val deadline = e.atMillis + cfg.pauseMillis
                st.to(ConvState.Pausing(s.utt, deadline), ConvEffect.StartTimer(deadline))
            }
        else -> st.same()
    }

private fun onTick(st: ConvStatus, s: ConvState, e: ConvEvent.Tick, cfg: ConvSettings): Pair<ConvStatus, List<ConvEffect>> {
    if (s !is ConvState.Pausing || e.atMillis < s.deadlineMillis) return st.same()
    if (s.utt.isSpeck()) return st.to(ConvState.Idle) // a dot or a slip of the pen: not worth a request
    return if (cfg.listening) startRequest(st, s.utt) else st.to(ConvState.Held(s.utt))
}

/** Writing too small to mean anything (page units): a dot, a tap of the nib. */
private const val SPECK = 12f

private fun Utterance.isSpeck() = purpose is Purpose.Writing && bounds.width < SPECK && bounds.height < SPECK

/** Erasing starts with a pen-down (Writing): what the utterance still has; nothing left = nothing to ask. */
private fun onErased(st: ConvStatus, s: ConvState, e: ConvEvent.Erased): Pair<ConvStatus, List<ConvEffect>> {
    val utt = (s as? ConvState.Writing)?.utt ?: return st.same()
    val bounds = e.bounds
    return when {
        e.alive.isEmpty() || bounds == null -> st.to(ConvState.Writing(null))
        !utt.strokeIds.containsAll(e.alive) -> st.same() // computed against another utterance: ignore
        else -> st.to(ConvState.Writing(utt.copy(strokeIds = e.alive, bounds = bounds)))
    }
}

private fun startRequest(st: ConvStatus, utt: Utterance, vararg before: ConvEffect): Pair<ConvStatus, List<ConvEffect>> {
    val req = st.lastReqId + 1
    val fx = before.toList() + ConvEffect.ShowInkwell(utt) + ConvEffect.SendRequest(utt, req)
    return ConvStatus(ConvState.Thinking(utt, req), req) to fx
}

private fun onAskNow(st: ConvStatus, s: ConvState) = when (s) {
    is ConvState.Pausing -> startRequest(st, s.utt, ConvEffect.CancelTimer)
    is ConvState.Held -> startRequest(st, s.utt)
    else -> st.same()
}

/**
 * Only from a quiet page (idle, or after a stopped answer). A lesson's return (and writing kept from before) waits for
 * Ask now when listening is off; what was asked for explicitly (again, tidy, check, a lasso, a lesson's opening) always
 * goes, and also takes over from writing that was only waiting for its pause (that writing is usually what they then
 * lassoed).
 */
private fun onBegin(st: ConvStatus, s: ConvState, e: ConvEvent.Begin, cfg: ConvSettings) = when {
    e.utt.purpose.explicit && s is ConvState.Pausing -> startRequest(st, e.utt, ConvEffect.CancelTimer)
    e.utt.purpose.explicit && s is ConvState.Held -> startRequest(st, e.utt)
    s != ConvState.Idle && s !is ConvState.Interrupted -> st.same()
    cfg.listening || e.utt.purpose.explicit -> startRequest(st, e.utt)
    else -> st.to(ConvState.Held(e.utt))
}

private fun onSilent(st: ConvStatus, s: ConvState, e: ConvEvent.OracleSilent) =
    if (s is ConvState.Thinking && s.reqId == e.reqId) {
        st.to(ConvState.Idle, ConvEffect.HideInkwell(dry = true), ConvEffect.Record(e.reqId, ExchangeStatus.Silent))
    } else st.same()

private fun onBlock(st: ConvStatus, s: ConvState, e: ConvEvent.OracleBlock) = when {
    s is ConvState.Thinking && s.reqId == e.reqId ->
        st.to(ConvState.Scribing(s.reqId, blocks = 1), ConvEffect.HideInkwell(dry = false), ConvEffect.PlanBlock(e.reqId, e.block))
    s is ConvState.Scribing && s.reqId == e.reqId ->
        st.to(s.copy(scribeDone = false, blocks = s.blocks + 1), ConvEffect.PlanBlock(e.reqId, e.block))
    // stopped by the pen, still arriving: planned now, written if they tap Continue
    s is ConvState.Interrupted && s.reqId == e.reqId -> st.to(s.copy(blocks = s.blocks + 1), ConvEffect.PlanBlock(e.reqId, e.block))
    else -> st.same()
}

private fun onDone(st: ConvStatus, s: ConvState, e: ConvEvent.OracleDone) = when {
    s is ConvState.Thinking && s.reqId == e.reqId -> // the model said nothing at all
        st.to(
            ConvState.Failed(s.utt, DomainError.Invalid("empty answer")),
            ConvEffect.HideInkwell(dry = true), ConvEffect.Record(e.reqId, ExchangeStatus.Failed),
            ConvEffect.ShowFailure(DomainError.Invalid("empty answer")),
        )
    s is ConvState.Scribing && s.reqId == e.reqId -> finishIfDone(st, s.copy(streamDone = true))
    s is ConvState.Interrupted && s.reqId == e.reqId -> st.to(s.copy(streamOpen = false))
    else -> st.same()
}

/** Only a report covering every block handed out counts: a late one for an earlier block must not end a live answer. */
private fun onScribeFinished(st: ConvStatus, s: ConvState, e: ConvEvent.ScribeFinished) =
    if (s is ConvState.Scribing && s.reqId == e.reqId && e.blocksDrawn >= s.blocks) finishIfDone(st, s.copy(scribeDone = true))
    else st.same()

private fun finishIfDone(st: ConvStatus, s: ConvState.Scribing) =
    if (s.streamDone && s.scribeDone) {
        st.to(ConvState.Idle, ConvEffect.Record(s.reqId, if (s.streamFailed) ExchangeStatus.Interrupted else ExchangeStatus.Complete))
    } else {
        st.to(s)
    }

private fun onFailed(st: ConvStatus, s: ConvState, e: ConvEvent.OracleFailed) = when {
    s is ConvState.Thinking && s.reqId == e.reqId ->
        st.to(
            ConvState.Failed(s.utt, e.error),
            ConvEffect.HideInkwell(dry = true), ConvEffect.Record(e.reqId, ExchangeStatus.Failed), ConvEffect.ShowFailure(e.error),
        )
    // The answer already started: keep the ink written so far and finish as interrupted (quietly, unless it was cut short).
    s is ConvState.Scribing && s.reqId == e.reqId -> {
        val (next, fx) = finishIfDone(st, s.copy(streamDone = true, streamFailed = true))
        next to (if (e.error == DomainError.Invalid(CUT_SHORT)) fx + ConvEffect.ShowCutShort else fx)
    }
    s is ConvState.Interrupted && s.reqId == e.reqId -> st.to(s.copy(streamOpen = false))
    else -> st.same()
}

private fun onTapContinue(st: ConvStatus, s: ConvState) =
    if (s is ConvState.Interrupted) {
        st.to(ConvState.Scribing(s.reqId, streamDone = !s.streamOpen, blocks = s.blocks), ConvEffect.ResumeScribe(s.reqId))
    } else st.same()

private fun onTapRetry(st: ConvStatus, s: ConvState) =
    if (s is ConvState.Failed) startRequest(st, s.utt, ConvEffect.ClearFailure) else st.same()
