package com.nisaba.domain.conversation

import com.nisaba.domain.conversation.ConversationSession.Companion.LESSON_TOP
import com.nisaba.domain.conversation.ConversationSession.Companion.RESUME_AFTER_MILLIS
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.union
import kotlinx.coroutines.launch

// Nisaba writing without new handwriting, or with kept writing: a lesson opening, another go at an answer,
// a tidy copy, a question kept from another page or from offline.

/**
 * A lesson notebook's [pageId] was opened; [belowY] = the bottom of the ink on it (null: the page is blank).
 * A fresh lesson on a blank page: Nisaba opens it from the top. Back after [RESUME_AFTER_MILLIS]: it recaps and
 * re-checks below the last ink. Otherwise nothing happens.
 */
fun ConversationSession.beginLesson(pageId: PageId, belowY: Float? = null) {
    if (closed) return
    scope.launch {
        val last = deps.exchanges.listByNotebook(deps.notebookId)
            .filter { it.status == ExchangeStatus.Complete || it.status == ExchangeStatus.Interrupted }
            .maxOfOrNull { it.createdAtMillis }
        val purpose = when {
            last == null -> Purpose.Opening.takeIf { belowY == null }
            deps.clock.nowMillis() - last >= RESUME_AFTER_MILLIS -> Purpose.Resume
            else -> null
        } ?: return@launch
        dispatch(ConvEvent.Begin(Utterance(pageId, emptySet(), at(belowY ?: LESSON_TOP), purpose)))
    }
}

/**
 * Writing still waiting for an answer when the page is left: paused on, being thought about, or failed for want of
 * a connection. (Writing held back while muted is the person's choice, and stays held.)
 */
fun ConversationSession.pendingUtterance(): Utterance? = when (val s = statusFlow.value.state) {
    is ConvState.Pausing -> s.utt
    is ConvState.Thinking -> s.utt.takeIf { it.purpose is Purpose.Writing }
    is ConvState.Failed -> s.utt.takeIf { s.error is com.nisaba.domain.shared.DomainError.Network && it.purpose is Purpose.Writing }
    else -> null
}

/** A question kept from before (the page was left, or the connection was down): ask it now. */
fun ConversationSession.askPending(utt: Utterance) = dispatch(ConvEvent.Begin(utt))

/** The connection is back: a question that failed for want of it is asked again. */
fun ConversationSession.retryIfOffline() {
    val s = statusFlow.value.state
    if (s is ConvState.Failed && s.error is com.nisaba.domain.shared.DomainError.Network) dispatch(ConvEvent.TapRetry)
}

/** Re-asks [previous] (its answer was just erased) on [pageId]. */
/**
 * Another go at [previous] on [pageId]. When [how] keeps the old answer, [answerBounds] (where it is on the page) moves
 * the new one below it and puts it in the focus image too.
 */
fun ConversationSession.askAgain(previous: Exchange, pageId: PageId, how: AgainHow = AgainHow.Afresh, answerBounds: PageRect? = null) {
    val focus = previous.focus ?: return
    val area = if (how.keepsOld && answerBounds != null) focus.union(answerBounds) else focus
    dispatch(ConvEvent.Begin(Utterance(pageId, emptySet(), area, Purpose.Again(previous.heard, previous.answerMarkup, how))))
}

/** Writes a clean copy of [source]'s notes on [pageId] (a fresh page), from the top. */
fun ConversationSession.tidy(source: PageId, pageId: PageId) =
    dispatch(ConvEvent.Begin(Utterance(pageId, emptySet(), at(LESSON_TOP), Purpose.Tidy(source))))

internal fun ConversationSession.at(y: Float) = PageRect(ANSWER_MARGIN, y, ANSWER_MARGIN, y)

/** Checks the work in [strokes] (their handwriting on [pageId]): a ✓ or ✗ beside each line. False: nothing to check. */
fun ConversationSession.check(pageId: PageId, strokes: List<com.nisaba.domain.ink.Stroke>): Boolean {
    val lines = com.nisaba.domain.ink.inkLines(strokes.filter { it.origin is com.nisaba.domain.ink.StrokeOrigin.User })
    if (lines.isEmpty()) return false
    val ids = lines.flatMap { l -> l.strokes.map { it.id } }.toSet()
    val area = lines.map { it.bounds }.reduce { a, b -> a.union(b) }
    dispatch(ConvEvent.Begin(Utterance(pageId, ids, area, Purpose.Check(lines.map { it.bounds }))))
    return true
}

/** Redraws the sketch in [strokes] cleanly, below it. */
fun ConversationSession.redraw(pageId: PageId, strokes: List<com.nisaba.domain.ink.Stroke>) {
    val area = com.nisaba.domain.ink.utteranceBounds(strokes) ?: return
    dispatch(ConvEvent.Begin(Utterance(pageId, strokes.mapTo(HashSet()) { it.id }, area, Purpose.Redraw)))
}

/** They looped [area] on [pageId] (around [ids], or around printed text when empty) and ask about it. */
fun ConversationSession.askAbout(pageId: PageId, ids: Set<com.nisaba.domain.shared.StrokeId>, area: PageRect) =
    dispatch(ConvEvent.Begin(Utterance(pageId, ids, area, Purpose.About)))

/** Writes [purpose] (a quiz, today's page, the week's recap, a review) on [pageId], a fresh page, from the top. */
fun ConversationSession.writeFresh(pageId: PageId, purpose: Purpose) {
    if (!purpose.textOnly) return
    dispatch(ConvEvent.Begin(Utterance(pageId, emptySet(), at(LESSON_TOP), purpose)))
}
