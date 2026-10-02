package com.nisaba.domain.conversation

import com.nisaba.domain.answer.AnswerParser
import com.nisaba.domain.answer.ParseEvent
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
)

/**
 * Drives one open page's ask loop: feeds pen/stream/timer events into [reduce] and executes the resulting effects
 * (timer, request job, surface calls, exchange persistence). One session per page; [close] when leaving it.
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

    /** Book-keeping for one request so the exchange can be saved once both its outcome and its stream are final. */
    private class PendingExchange(val reqId: Int, val pageId: PageId, val startedAt: Long) {
        val markup = StringBuilder()
        var heard = ""
        var inputTokens = 0
        var outputTokens = 0
        var status: ExchangeStatus? = null
        var streamFinished = false
        var cancelled = false
    }

    fun updateSettings(settings: Settings, listening: Boolean) {
        appSettings = settings
        cfg = ConvSettings(settings.pauseMillis.toLong(), listening)
    }

    fun onPenDown() = dispatch(ConvEvent.PenDown)
    fun onPenUp(strokeId: StrokeId, bounds: PageRect, pageId: PageId) =
        dispatch(ConvEvent.PenUp(strokeId, bounds, pageId, deps.clock.nowMillis()))
    fun onPenAborted() = dispatch(ConvEvent.PenAborted(deps.clock.nowMillis()))
    fun onScribeFinished() = dispatch(ConvEvent.ScribeFinished)
    fun onTapContinue() = dispatch(ConvEvent.TapContinue)
    fun onTapRetry() = dispatch(ConvEvent.TapRetry)
    fun onAskNow() = dispatch(ConvEvent.AskNow)

    fun close() {
        timerJob?.cancel(); requestJob?.cancel()
    }

    private fun dispatch(e: ConvEvent) {
        scope.launch {
            lock.withLock {
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
            is ConvEffect.PlanBlock -> deps.surface.planBlock(fx.reqId, fx.block)
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
        val record = PendingExchange(reqId, utt.pageId, deps.clock.nowMillis())
        lock.withLock { pending[reqId] = record }
        try {
            val margin = utt.bounds.height.coerceAtLeast(FOCUS_MIN) * FOCUS_MARGIN
            val snapshot = deps.snapshotter.snapshot(utt.pageId, utt.bounds.inflate(margin))
            val request = buildRequest(
                ContextInput(
                    deps.context.documentPages(), deps.context.currentDocumentPage,
                    deps.exchanges.listByNotebook(deps.notebookId), snapshot, appSettings,
                ),
            )
            stream(request, record)
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
                is ParseEvent.Heard -> record.heard = ev.text
            }
        }
        deps.oracle.ask(request).collect { ev ->
            when (ev) {
                is OracleEvent.Delta -> { record.markup.append(ev.text); forward(parser.feed(ev.text)) }
                is OracleEvent.Usage -> { record.inputTokens = ev.promptTokens; record.outputTokens = ev.completionTokens }
            }
        }
        forward(parser.finish())
    }

    /** Saves the exchange once its status is decided AND its stream is over (heard text arrives last). */
    private fun maybeFinalize(p: PendingExchange) {
        val status = p.status ?: return
        if (!p.streamFinished || pending.remove(p.reqId) == null) return
        val exchange = Exchange(
            ExchangeId(deps.idGen.next()), deps.notebookId, p.pageId, p.heard, p.markup.toString(), status, p.startedAt,
            appSettings.model, PROMPT_VERSION, deps.clock.nowMillis() - p.startedAt, p.inputTokens, p.outputTokens,
        )
        scope.launch { deps.exchanges.append(exchange) }
    }

    private companion object {
        const val FOCUS_MARGIN = 0.1f
        const val FOCUS_MIN = 100f
    }
}
