package com.nisaba.domain.conversation

import com.nisaba.domain.answer.AnswerParser
import com.nisaba.domain.answer.ParseEvent
import com.nisaba.domain.lesson.LessonStore
import com.nisaba.domain.memory.ApplyMemoryUseCase
import com.nisaba.domain.memory.MemoryStore
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
    internal val deps: ConversationDeps,
    internal val scope: CoroutineScope,
    settings: Settings = Settings(),
    listening: Boolean = true,
) {
    internal val statusFlow = MutableStateFlow(ConvStatus())
    val status: StateFlow<ConvStatus> = statusFlow.asStateFlow()

    internal val lock = Mutex()
    internal var cfg = ConvSettings(settings.pauseMillis.toLong(), listening)
    internal var appSettings = settings
    internal var timerJob: Job? = null
    internal var requestJob: Job? = null
    internal val pending = HashMap<Int, PendingExchange>()
    internal val applyMemory = deps.memory?.let { ApplyMemoryUseCase(it, deps.clock) }
    internal var closed = false

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

    internal fun at(y: Float) = PageRect(ANSWER_MARGIN, y, ANSWER_MARGIN, y)

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

    internal fun dispatch(e: ConvEvent) {
        if (closed) return
        scope.launch {
            lock.withLock {
                if (closed) return@withLock
                val (next, effects) = reduce(statusFlow.value, e, cfg)
                statusFlow.value = next
                effects.forEach(::run)
            }
        }
    }

    internal fun run(fx: ConvEffect) {
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
            ConvEffect.ShowCutShort -> deps.surface.showCutShort()
            is ConvEffect.Record -> pending[fx.reqId]?.let { it.status = fx.status; maybeFinalize(it) }
        }
    }

    internal fun startTimer(at: Long) {
        timerJob?.cancel()
        timerJob = scope.launch {
            delay((at - deps.clock.nowMillis()).coerceAtLeast(0))
            dispatch(ConvEvent.Tick(deps.clock.nowMillis()))
        }
    }

    internal suspend fun runRequest(utt: Utterance, reqId: Int) {
        val purpose = utt.purpose
        val answersWriting = purpose is Purpose.Writing || purpose is Purpose.Again
        val focus = utt.bounds.takeIf { answersWriting }
        val persona = deps.context.persona
        val record = PendingExchange(
            reqId, ExchangeId(deps.idGen.next()), utt.pageId, deps.clock.nowMillis(), focus, answersWriting, persona.id,
        )
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
                    deps.memory?.all().orEmpty(), deps.context.teachGoal, purpose, lessonProgress(), persona,
                ),
            )
            stream(request, record)
            val cut = record.ended != null && record.ended != EndReason.Finished
            if (cut) {
                deps.logger?.w("Session", "request $reqId ended early: ${record.ended}")
                dispatch(ConvEvent.OracleFailed(reqId, DomainError.Invalid(CUT_SHORT))) // ink stays; nothing learned
            } else {
                record.replyComplete = true
                checkpoint(record)
                dispatch(ConvEvent.OracleDone(reqId))
            }
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

    internal suspend fun stream(request: OracleRequest, record: PendingExchange) {
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
                is OracleEvent.Ended -> record.ended = ev.reason
            }
        }
        forward(parser.finish())
    }

    internal companion object {
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
