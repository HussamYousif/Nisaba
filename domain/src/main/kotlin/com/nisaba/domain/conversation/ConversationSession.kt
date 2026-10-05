package com.nisaba.domain.conversation

import com.nisaba.domain.answer.AnswerParser
import com.nisaba.domain.answer.ParseEvent
import com.nisaba.domain.lesson.LessonStore
import com.nisaba.domain.lesson.formatLessonProgress
import com.nisaba.domain.lesson.parseTopics
import com.nisaba.domain.memory.ApplyMemoryUseCase
import com.nisaba.domain.memory.MemoryStore
import com.nisaba.domain.memory.memoryNote
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.Clock
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.IdGen
import com.nisaba.domain.shared.Logger
import com.nisaba.domain.shared.NetworkKind
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.StrokeId
import com.nisaba.domain.shared.inflate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ConversationDeps(
    val notebookId: NotebookId,
    val oracle: Oracle,
    val snapshotter: PageSnapshotter,
    val exchanges: ExchangeStore,
    val surface: ConversationSurface,
    val context: ConversationContextPorts,
    val clock: Clock,
    val idGen: IdGen,
    val logger: Logger? = null,
    /** Long-term memory shared by all notebooks; null = the notebook neither sees nor learns facts. */
    val memory: MemoryStore? = null,
    /** Lesson progress; used only in lesson notebooks. */
    val lessons: LessonStore? = null,
)

/**
 * Drives one open page's ask loop: feeds pen/stream/timer events into [reduce] and executes the resulting effects
 * (timer, request job, surface calls, exchange persistence, memory tags). One session per page; [close] when leaving it.
 */
class ConversationSession(
    private val deps: ConversationDeps,
    private val scope: CoroutineScope,
    settings: Settings = Settings(),
    listening: Boolean = true,
) {
    private val _status = MutableStateFlow(ConvStatus())
    val status: StateFlow<ConvStatus> = _status.asStateFlow()

    private val lock = Mutex()
    private var cfg = ConvSettings(settings.pauseMillis.toLong(), listening)
    private var appSettings = settings
    private var timerJob: Job? = null
    private var requestJob: Job? = null
    private val pending = HashMap<Int, PendingExchange>()
    private val applyMemory = deps.memory?.let { ApplyMemoryUseCase(it, deps.clock) }
    private var closed = false

    /** Book-keeping for one request so the exchange can be saved once both its outcome and its stream are final. */
    /** [transcribes] = the reply's `<heard>` is a transcription of new writing (not of a whole page Nisaba was asked about). */
    private class PendingExchange(
        val reqId: Int,
        val id: ExchangeId,
        val pageId: PageId,
        val startedAt: Long,
        val focus: PageRect?,
        val transcribes: Boolean,
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
    }

    fun updateSettings(settings: Settings, listening: Boolean) {
        appSettings = settings
        cfg = ConvSettings(settings.pauseMillis.toLong(), listening)
    }

    fun onPenDown() = dispatch(ConvEvent.PenDown)
    fun onPenUp(strokeId: StrokeId, bounds: PageRect, pageId: PageId) =
        dispatch(ConvEvent.PenUp(strokeId, bounds, pageId, deps.clock.nowMillis()))
    fun onPenAborted() = dispatch(ConvEvent.PenAborted(deps.clock.nowMillis()))
    fun onScribeFinished(reqId: Int, blocksDrawn: Int) = dispatch(ConvEvent.ScribeFinished(reqId, blocksDrawn))
    fun onTapContinue() = dispatch(ConvEvent.TapContinue)
    fun onTapRetry() = dispatch(ConvEvent.TapRetry)
    fun onAskNow() = dispatch(ConvEvent.AskNow)

    /**
     * A lesson notebook's [pageId] was opened; [belowY] = the bottom of the ink on it (null: the page is blank).
     * A fresh lesson on a blank page: Nisaba opens it from the top. Back after [RESUME_AFTER_MILLIS]: it recaps and
     * re-checks below the last ink. Otherwise nothing happens.
     */
    fun beginLesson(pageId: PageId, belowY: Float? = null) {
        if (closed) return
        scope.launch {
            val last = deps.exchanges.listByNotebook(deps.notebookId).maxOfOrNull { it.createdAtMillis }
            val purpose = when {
                last == null -> Purpose.Opening.takeIf { belowY == null }
                deps.clock.nowMillis() - last >= RESUME_AFTER_MILLIS -> Purpose.Resume
                else -> null
            } ?: return@launch
            dispatch(ConvEvent.Begin(Utterance(pageId, emptySet(), at(belowY ?: LESSON_TOP), purpose)))
        }
    }

    /** Re-asks [previous] (its answer was just erased) on [pageId]. */
    fun askAgain(previous: Exchange, pageId: PageId) {
        val focus = previous.focus ?: return
        dispatch(ConvEvent.Begin(Utterance(pageId, emptySet(), focus, Purpose.Again(previous.heard, previous.answerMarkup))))
    }

    /** Writes a clean copy of [source]'s notes on [pageId] (a fresh page), from the top. */
    fun tidy(source: PageId, pageId: PageId) =
        dispatch(ConvEvent.Begin(Utterance(pageId, emptySet(), at(LESSON_TOP), Purpose.Tidy(source))))

    private fun at(y: Float) = PageRect(ANSWER_MARGIN, y, ANSWER_MARGIN, y)

    /**
     * Leaves the page. An answer that already started is kept as Interrupted (its ink stays on the page); one that
     * never showed anything leaves no trace. Saving outlives [scope] being cancelled.
     */
    fun close() {
        if (closed) return
        closed = true
        timerJob?.cancel(); requestJob?.cancel()
        scope.launch(NonCancellable) {
            lock.withLock {
                // stream over, hand still writing: nothing else will record it
                pending.values.filter { it.streamFinished && it.status == null }.forEach {
                    it.status = ExchangeStatus.Interrupted; maybeFinalize(it)
                }
            }
        }
    }

    private fun dispatch(e: ConvEvent) {
        if (closed) return
        scope.launch {
            lock.withLock {
                if (closed) return@withLock
                val (next, effects) = reduce(_status.value, e, cfg)
                _status.value = next
                effects.forEach(::run)
            }
        }
    }

    private fun run(fx: ConvEffect) {
        when (fx) {
            is ConvEffect.StartTimer -> startTimer(fx.atMillis)
            ConvEffect.CancelTimer -> timerJob?.cancel()
            is ConvEffect.SendRequest -> requestJob = scope.launch { runRequest(fx.utt, fx.reqId) }
            is ConvEffect.CancelRequest -> requestJob?.cancel()
            is ConvEffect.ShowInkwell -> deps.surface.showInkwell(fx.utt)
            is ConvEffect.HideInkwell -> deps.surface.hideInkwell(fx.dry)
            is ConvEffect.PlanBlock -> pending[fx.reqId]?.let { deps.surface.planBlock(fx.reqId, fx.block, it.id) }
            is ConvEffect.StopScribe -> deps.surface.stopScribe(fx.reqId)
            is ConvEffect.ResumeScribe -> deps.surface.resumeScribe(fx.reqId)
            is ConvEffect.ShowFailure -> deps.surface.showFailure(fx.error)
            ConvEffect.ClearFailure -> deps.surface.clearFailure()
            is ConvEffect.Record -> pending[fx.reqId]?.let { it.status = fx.status; maybeFinalize(it) }
        }
    }

    private fun startTimer(at: Long) {
        timerJob?.cancel()
        timerJob = scope.launch {
            delay((at - deps.clock.nowMillis()).coerceAtLeast(0))
            dispatch(ConvEvent.Tick(deps.clock.nowMillis()))
        }
    }

    private suspend fun runRequest(utt: Utterance, reqId: Int) {
        val purpose = utt.purpose
        val answersWriting = purpose is Purpose.Writing || purpose is Purpose.Again
        val focus = utt.bounds.takeIf { answersWriting }
        val record = PendingExchange(reqId, ExchangeId(deps.idGen.next()), utt.pageId, deps.clock.nowMillis(), focus, answersWriting)
        record.heard = when (purpose) {
            Purpose.Opening -> OPENING_HEARD
            Purpose.Resume -> RESUME_HEARD
            is Purpose.Tidy -> TIDY_HEARD
            is Purpose.Again -> purpose.previousHeard
            Purpose.Writing -> ""
        }
        lock.withLock { pending[reqId] = record }
        try {
            val margin = utt.bounds.height.coerceAtLeast(FOCUS_MIN) * FOCUS_MARGIN
            val source = (purpose as? Purpose.Tidy)?.sourcePageId ?: utt.pageId
            val snapshot = deps.snapshotter.snapshot(source, if (answersWriting) utt.bounds.inflate(margin) else null)
            val request = buildRequest(
                ContextInput(
                    deps.context.documentPages(), deps.context.currentDocumentPage,
                    deps.exchanges.listByNotebook(deps.notebookId), snapshot, appSettings,
                    deps.memory?.all().orEmpty(), deps.context.teachGoal, purpose, lessonProgress(),
                ),
            )
            stream(request, record)
            record.replyComplete = true
            dispatch(ConvEvent.OracleDone(reqId))
        } catch (e: CancellationException) {
            record.cancelled = true
            throw e
        } catch (e: OracleException) {
            deps.logger?.w("Session", "request $reqId failed: ${e.error}", e)
            dispatch(ConvEvent.OracleFailed(reqId, e.error))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            deps.logger?.e("Session", "request $reqId crashed", e)
            dispatch(ConvEvent.OracleFailed(reqId, DomainError.Network(NetworkKind.Offline)))
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    record.streamFinished = true
                    // Leaving the page mid-answer: keep what was already written.
                    if (closed && record.status == null && record.markup.isNotEmpty()) record.status = ExchangeStatus.Interrupted
                    // Cancelled before any outcome was recorded (pen-down while thinking): nothing to keep.
                    if (record.cancelled && record.status == null) pending.remove(record.reqId) else maybeFinalize(record)
                }
            }
        }
    }

    private suspend fun stream(request: OracleRequest, record: PendingExchange) {
        val parser = AnswerParser()
        fun forward(events: List<ParseEvent>) = events.forEach { ev ->
            when (ev) {
                ParseEvent.Silent -> dispatch(ConvEvent.OracleSilent(record.reqId))
                is ParseEvent.BlockReady -> dispatch(ConvEvent.OracleBlock(record.reqId, ev.block))
                is ParseEvent.Heard -> if (record.transcribes && ev.text.isNotBlank()) record.heard = ev.text
            }
        }
        deps.oracle.ask(request).collect { ev ->
            when (ev) {
                is OracleEvent.Delta -> { record.markup.append(ev.text); forward(parser.feed(ev.text)) }
                is OracleEvent.Usage -> {
                    record.inputTokens = ev.promptTokens; record.outputTokens = ev.completionTokens; record.cachedTokens = ev.cachedTokens
                }
            }
        }
        forward(parser.finish())
    }

    /** Saves the exchange once its status is decided AND its stream is over (heard text arrives last). */
    private fun maybeFinalize(p: PendingExchange) {
        val status = p.status ?: return
        if (!p.streamFinished || pending.remove(p.reqId) == null) return
        val exchange = Exchange(
            p.id, deps.notebookId, p.pageId, p.heard, p.markup.toString(), status, p.startedAt,
            appSettings.model, PROMPT_VERSION, deps.clock.nowMillis() - p.startedAt, p.inputTokens, p.outputTokens,
            p.cachedTokens, p.focus,
        )
        scope.launch(NonCancellable) { // the page may be closing; the exchange is still saved
            deps.exchanges.append(exchange)
            if (p.replyComplete && (status == ExchangeStatus.Complete || status == ExchangeStatus.Interrupted)) {
                remember(exchange); recordTopics(exchange)
            }
        }
    }

    private suspend fun lessonProgress(): String? {
        val store = deps.lessons ?: return null
        if (deps.context.teachGoal == null) return null
        return formatLessonProgress(store.topics(deps.notebookId))
    }

    /** Records the reply's `<topic>` tags in a lesson notebook; like memory, a hiccup never disturbs the page. */
    private suspend fun recordTopics(exchange: Exchange) {
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
    private suspend fun remember(exchange: Exchange) {
        val apply = applyMemory ?: return
        try {
            apply(exchange.answerMarkup, exchange.id)?.let(::memoryNote)?.let(deps.surface::showMemoryNote)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            deps.logger?.e("Session", "memory update failed", e)
        }
    }

    private companion object {
        const val FOCUS_MARGIN = 0.1f
        const val FOCUS_MIN = 100f
        const val LESSON_TOP = 160f

        /** What the person "said" when Nisaba opened the lesson, as history shows it. */
        const val OPENING_HEARD = "(opens the lesson notebook)"
        const val RESUME_HEARD = "(comes back to the lesson)"
        const val TIDY_HEARD = "(asks for a tidy copy of a page of notes)"
        const val RESUME_AFTER_MILLIS = 4 * 60 * 60 * 1000L
    }
}
