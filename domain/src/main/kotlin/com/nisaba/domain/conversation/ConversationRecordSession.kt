package com.nisaba.domain.conversation

import com.nisaba.domain.conversation.ConversationSession.PendingExchange

import com.nisaba.domain.lesson.formatLessonProgress
import com.nisaba.domain.lesson.parseTopics
import com.nisaba.domain.memory.memoryNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

// Saving what was said: the provisional save at the reply end, the final save, and what the reply taught (memory, lesson topics).

/**
 * The reply is in but the hand may write for a while yet: save it now as Interrupted (what it is if the app dies
 * before the hand finishes). The final save replaces it. Queued like an event (after the reply's blocks, before its
 * end) and saved under [lock], so it can never land after the final save.
 */
internal fun ConversationSession.checkpoint(p: PendingExchange) {
    scope.launch {
        lock.withLock {
            val writing = (statusFlow.value.state as? ConvState.Scribing)?.reqId == p.reqId
            if (closed || p.status != null || !writing) return@withLock
            try {
                deps.exchanges.append(exchangeOf(p, ExchangeStatus.Interrupted))
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                deps.logger?.e("Session", "checkpoint save failed", e)
            }
        }
    }
}

internal fun ConversationSession.exchangeOf(p: PendingExchange, status: ExchangeStatus) = Exchange(
    p.id, deps.notebookId, p.pageId, p.heard, p.markup.toString(), status, p.startedAt,
    appSettings.model, PROMPT_VERSION, deps.clock.nowMillis() - p.startedAt, p.inputTokens, p.outputTokens,
    p.cachedTokens, p.focus, p.personaId,
)

/** Saves the exchange once its status is decided AND its stream is over (heard text arrives last). */
internal fun ConversationSession.maybeFinalize(p: PendingExchange) {
    val status = p.status ?: return
    if (!p.streamFinished || pending.remove(p.reqId) == null) return
    val exchange = exchangeOf(p, status)
    scope.launch(NonCancellable) { // the page may be closing; the exchange is still saved
        try {
            deps.exchanges.append(exchange)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) { // a full disk must not take the page down
            deps.logger?.e("Session", "saving exchange ${exchange.id.value} failed", e)
            return@launch
        }
        if (p.replyComplete && (status == ExchangeStatus.Complete || status == ExchangeStatus.Interrupted)) {
            remember(exchange); recordTopics(exchange)
        }
    }
}

internal suspend fun ConversationSession.lessonProgress(): String? {
    val store = deps.lessons ?: return null
    if (deps.context.teachGoal == null) return null
    return formatLessonProgress(store.topics(deps.notebookId))
}

/** Records the reply's `<topic>` tags in a lesson notebook; like memory, a hiccup never disturbs the page. */
internal suspend fun ConversationSession.recordTopics(exchange: Exchange) {
    val store = deps.lessons ?: return
    if (deps.context.teachGoal == null) return
    val topics = parseTopics(exchange.answerMarkup, deps.clock.nowMillis()).takeIf { it.isNotEmpty() } ?: return
    try {
        store.upsert(deps.notebookId, topics)
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        deps.logger?.e("Session", "lesson progress update failed", e)
    }
}

/** Applies the reply's `<remember>`/`<forget>` tags; a memory hiccup never disturbs the page. */
internal suspend fun ConversationSession.remember(exchange: Exchange) {
    val apply = applyMemory ?: return
    try {
        apply(exchange.answerMarkup, exchange.id)?.let(::memoryNote)?.let(deps.surface::showMemoryNote)
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        deps.logger?.e("Session", "memory update failed", e)
    }
}
