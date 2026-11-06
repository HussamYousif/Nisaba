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
    /** A book notebook's map, marked as the walk writes its pages; used only in book notebooks. */
    val books: com.nisaba.domain.book.BookStore? = null,
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
    /** Where requests are built (a PDF's text windowed and trimmed); null = where the session runs (tests). */
    private val compute: kotlinx.coroutines.CoroutineDispatcher? = null,
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
    internal val consolidate = deps.memory?.let { com.nisaba.domain.memory.ConsolidateMemoryUseCase(it, deps.oracle, deps.clock) }
    internal var closed = false

    fun updateSettings(settings: Settings, listening: Boolean) {
        appSettings = settings
        cfg = ConvSettings(settings.pauseMillis.toLong(), listening)
    }

    fun onPenDown() = dispatch(ConvEvent.PenDown)
    fun onPenUp(strokeId: StrokeId, bounds: PageRect, pageId: PageId) {
        deps.oracle.warmUp() // the pause is coming: the request then skips the handshake
        dispatch(ConvEvent.PenUp(strokeId, bounds, pageId, deps.clock.nowMillis()))
    }
    fun onPenAborted() = dispatch(ConvEvent.PenAborted(deps.clock.nowMillis()))

    /** Ink was erased: the utterance keeps only its strokes still on the page; none left = nothing to ask. */
    fun onErased(onPage: List<com.nisaba.domain.ink.Stroke>) {
        val utt = (statusFlow.value.state as? ConvState.Writing)?.utt ?: return
        val alive = onPage.filter { it.id in utt.strokeIds }
        dispatch(ConvEvent.Erased(alive.mapTo(HashSet()) { it.id }, com.nisaba.domain.ink.utteranceBounds(alive)))
    }
    fun onScribeFinished(reqId: Int, blocksDrawn: Int) = dispatch(ConvEvent.ScribeFinished(reqId, blocksDrawn))
    fun onTapContinue() = dispatch(ConvEvent.TapContinue)
    fun onTapRetry() = dispatch(ConvEvent.TapRetry)
    fun onAskNow() = dispatch(ConvEvent.AskNow)

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
            is ConvEffect.PlanBlock -> pending[fx.reqId]?.let { deps.surface.planBlock(fx.reqId, fx.block, it.id, it.personaId) }
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

    /** What the person "said", as history shows it, when Nisaba writes without new writing to transcribe. */
    private fun heardFor(purpose: Purpose): String = when (purpose) {
        Purpose.Opening -> OPENING_HEARD
        Purpose.Resume -> RESUME_HEARD
        is Purpose.Tidy -> TIDY_HEARD
        is Purpose.Again -> purpose.previousHeard
        Purpose.Quiz -> QUIZ_HEARD
        Purpose.Daily -> DAILY_HEARD
        Purpose.Recap -> RECAP_HEARD
        is Purpose.Review -> REVIEW_HEARD
        is Purpose.Book -> com.nisaba.domain.book.bookHeard(deps.context.book, purpose.step)
        Purpose.Writing, is Purpose.Check, Purpose.Redraw, Purpose.About -> ""
    }

    /** The page as the model sees it: none for text-only requests; the tidy's source page; numbered lines for a check. */
    private suspend fun snapshotFor(utt: Utterance): PageSnapshot {
        val purpose = utt.purpose
        if (purpose.textOnly) return NO_PICTURE
        val source = (purpose as? Purpose.Tidy)?.sourcePageId ?: utt.pageId
        val labels = (purpose as? Purpose.Check)?.lines?.let(::lineLabels).orEmpty() // "1", "2", … beside the lines
        val focus = if (purpose.answersWriting) focusArea(utt.bounds) else null
        return deps.snapshotter.snapshot(source, focus, utt.strokeIds, labels)
    }

    internal suspend fun runRequest(utt: Utterance, reqId: Int) {
        val purpose = utt.purpose
        val answersWriting = purpose.answersWriting
        val focus = utt.bounds.takeIf { answersWriting }
        val other = ((purpose as? Purpose.Again)?.how as? AgainHow.OtherVoice)?.personaId
        val persona = other?.let(deps.context::voice) ?: deps.context.persona
        val record = PendingExchange(
            reqId, ExchangeId(deps.idGen.next()), utt.pageId, deps.clock.nowMillis(), focus, answersWriting, persona.id, purpose,
        )
        record.heard = heardFor(purpose)
        lock.withLock { pending[reqId] = record }
        var sent: OracleRequest? = null
        try {
            val snapshot = snapshotFor(utt)
            val private = deps.context.isPrivate // a private notebook neither sees memory nor teaches it
            val input = ContextInput(
                deps.context.documentPages(), deps.context.currentDocumentPage,
                deps.exchanges.listByNotebook(deps.notebookId), snapshot, appSettings,
                if (private) emptyList() else deps.memory?.all().orEmpty(), deps.context.teachGoal, purpose, lessonProgress(),
                persona, deps.context.style, now = nowInWords(deps.clock.nowMillis(), java.time.ZoneId.systemDefault()),
                isPrivate = private, book = bookPlace(),
            )
            // a whole PDF's text is windowed, estimated and trimmed here: never on the thread that draws the page
            val request = compute?.let { withContext(it) { buildRequest(input) } } ?: buildRequest(input)
            sent = request
            stream(request, record)
            streamEnded(record)
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
                    // Cancelled before any outcome (pen-down while thinking): nothing to show, but it was paid for.
                    if (record.cancelled && record.status == null) {
                        pending.remove(record.reqId); sent?.let { recordCancelled(record, it) }
                    } else {
                        maybeFinalize(record)
                    }
                }
            }
        }
    }

    /** The reply stopped: in full (saved at once, the hand may still be writing) or cut short (ink stays, nothing learned). */
    internal fun streamEnded(record: PendingExchange) {
        if (record.ended != null && record.ended != EndReason.Finished) {
            deps.logger?.w("Session", "request ${record.reqId} ended early: ${record.ended}")
            dispatch(ConvEvent.OracleFailed(record.reqId, DomainError.Invalid(CUT_SHORT)))
        } else {
            record.replyComplete = true
            checkpoint(record)
            dispatch(ConvEvent.OracleDone(record.reqId))
        }
    }

    internal suspend fun stream(request: OracleRequest, record: PendingExchange) {
        val parser = AnswerParser()
        fun forward(events: List<ParseEvent>) = events.forEach { ev ->
            when (ev) {
                ParseEvent.Silent -> dispatch(ConvEvent.OracleSilent(record.reqId))
                is ParseEvent.BlockReady -> {
                    if (record.firstBlockMillis == null) record.firstBlockMillis = deps.clock.nowMillis() - record.startedAt
                    dispatch(ConvEvent.OracleBlock(record.reqId, ev.block))
                }
                is ParseEvent.Heard -> if (record.transcribes && ev.text.isNotBlank()) record.heard = ev.text
            }
        }
        deps.oracle.ask(request).collect { ev ->
            when (ev) {
                is OracleEvent.Delta -> {
                    if (record.firstTokenMillis == null) record.firstTokenMillis = deps.clock.nowMillis() - record.startedAt
                    record.markup.append(ev.text); forward(parser.feed(ev.text))
                }
                is OracleEvent.Usage -> {
                    record.inputTokens = ev.promptTokens; record.outputTokens = ev.completionTokens; record.cachedTokens = ev.cachedTokens
                }
                is OracleEvent.Ended -> record.ended = ev.reason
            }
        }
        forward(parser.finish())
    }

    internal companion object {
        const val LESSON_TOP = 160f

        /** What the person "said" when Nisaba opened the lesson, as history shows it. */
        const val OPENING_HEARD = "(opens the lesson notebook)"
        const val RESUME_HEARD = "(comes back to the lesson)"
        const val TIDY_HEARD = "(asks for a tidy copy of a page of notes)"
        const val QUIZ_HEARD = "(asks to be quizzed on this notebook)"
        const val DAILY_HEARD = "(opens today's page)"
        const val RECAP_HEARD = "(asks for a recap of the week)"
        const val REVIEW_HEARD = "(asks to review what is due)"
        val NO_PICTURE = PageSnapshot(ByteArray(0), null)
        const val RESUME_AFTER_MILLIS = 4 * 60 * 60 * 1000L
    }
}
