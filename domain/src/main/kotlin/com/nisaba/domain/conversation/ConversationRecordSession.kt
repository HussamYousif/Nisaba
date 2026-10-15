package com.nisaba.domain.conversation


import com.nisaba.domain.lesson.formatLessonProgress
import com.nisaba.domain.lesson.parseTopics
import com.nisaba.domain.memory.memoryNote
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
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
    p.cachedTokens, p.focus, p.personaId, p.firstTokenMillis, p.firstBlockMillis,
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
            parseReminders(exchange.answerMarkup).forEach(deps.surface::offerReminder)
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
        val before = store.topics(deps.notebookId).associateBy { it.topic.lowercase() }
        store.upsert(deps.notebookId, topics.map { com.nisaba.domain.lesson.judged(before[it.topic.lowercase()], it) })
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        deps.logger?.e("Session", "lesson progress update failed", e)
    }
}

/** Applies the reply's `<remember>`/`<forget>` tags; a memory hiccup never disturbs the page. */
internal suspend fun ConversationSession.remember(exchange: Exchange) {
    if (deps.context.isPrivate) return // a private notebook teaches memory nothing
    val apply = applyMemory ?: return
    try {
        val plan = apply(exchange.answerMarkup, exchange.id)
        plan?.let(::memoryNote)?.let(deps.surface::showMemoryNote)
        if (plan != null && plan.add.isNotEmpty()) consolidate?.invoke(appSettings.model) // now and then, merge and tidy
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        deps.logger?.e("Session", "memory update failed", e)
    }
}

/** A request the pen cut off before any answer: never shown, but billed, so Usage still counts it (estimated). */
internal fun ConversationSession.recordCancelled(p: PendingExchange, request: OracleRequest) {
    val billed = exchangeOf(p, ExchangeStatus.Cancelled).copy(
        inputTokens = estimateTokens(request), outputTokens = estimateTokens(p.markup.toString()),
    )
    scope.launch(NonCancellable) {
        try {
            deps.exchanges.append(billed)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            deps.logger?.e("Session", "saving a cancelled request failed", e)
        }
    }
}

/** Book-keeping for one request so the exchange can be saved once both its outcome and its stream are final. */
/** [transcribes] = the reply's `<heard>` is a transcription of new writing (not of a whole page Nisaba was asked about). */
internal class PendingExchange(
    val reqId: Int,
    val id: ExchangeId,
    val pageId: PageId,
    val startedAt: Long,
    val focus: PageRect?,
    val transcribes: Boolean,
    val personaId: String,
) {
    val markup = StringBuilder()
    var heard = ""
    var inputTokens = 0
    var outputTokens = 0
    var cachedTokens = 0
    var status: ExchangeStatus? = null
    var streamFinished = false
    var cancelled = false

    /** The reply arrived in full (its memory tags can be trusted: a cut-off reply may hold a forget without its remember). */
    var replyComplete = false

    /** The provider said why it stopped; anything but Finished = the reply is incomplete. */
    var ended: EndReason? = null

    /** Milliseconds from the request to its first token, and to its first block (when the hand can start). */
    var firstTokenMillis: Long? = null
    var firstBlockMillis: Long? = null
}
