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
    is ConvState.Scribing -> {
        val fx = buildList {
            add(ConvEffect.StopScribe(s.reqId))
            if (!s.streamDone) add(ConvEffect.CancelRequest(s.reqId))
            add(ConvEffect.Record(s.reqId, ExchangeStatus.Interrupted))
        }
        st.copy(state = ConvState.Interrupted(s.reqId, s.blocks)) to fx
    }
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
    return if (cfg.listening) startRequest(st, s.utt) else st.to(ConvState.Held(s.utt))
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
 * Only from a quiet page (idle, or after a stopped answer). A lesson opening or return waits for Ask now when
 * listening is off; asking again or tidying was asked for explicitly, so it always goes.
 */
private fun onBegin(st: ConvStatus, s: ConvState, e: ConvEvent.Begin, cfg: ConvSettings) = when {
    s != ConvState.Idle && s !is ConvState.Interrupted -> st.same()
    cfg.listening || e.utt.purpose is Purpose.Again || e.utt.purpose is Purpose.Tidy -> startRequest(st, e.utt)
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
    else -> st.same()
}

private fun onTapContinue(st: ConvStatus, s: ConvState) =
    if (s is ConvState.Interrupted) {
        st.to(ConvState.Scribing(s.reqId, streamDone = true, blocks = s.blocks), ConvEffect.ResumeScribe(s.reqId))
    } else st.same()

private fun onTapRetry(st: ConvStatus, s: ConvState) =
    if (s is ConvState.Failed) startRequest(st, s.utt, ConvEffect.ClearFailure) else st.same()
