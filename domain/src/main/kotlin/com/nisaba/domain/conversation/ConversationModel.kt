package com.nisaba.domain.conversation

import com.nisaba.domain.answer.Block
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.StrokeId

/** Everything the user wrote on a page since the last exchange (one "utterance", however many pauses it spans). */
data class Utterance(val pageId: PageId, val strokeIds: Set<StrokeId>, val bounds: PageRect)

enum class ExchangeStatus { Complete, Interrupted, Silent, Failed }

data class Exchange(
    val id: ExchangeId,
    val notebookId: NotebookId,
    val pageId: PageId,
    val heard: String,
    val answerMarkup: String,
    val status: ExchangeStatus,
    val createdAtMillis: Long,
    val model: String,
    val promptVersion: Int,
    val latencyMillis: Long,
    val inputTokens: Int,
    val outputTokens: Int,
)

sealed interface Turn {
    data class User(val text: String) : Turn
    data class Assistant(val text: String) : Turn
}

enum class ImageDetail { Auto, High }

class OracleImage(val jpeg: ByteArray, val detail: ImageDetail)

data class OracleOptions(val model: String, val thinking: Boolean, val maxTokens: Int = 2048, val temperature: Double = 0.6)

/** Provider-agnostic request; adapters map it to their wire format. */
class OracleRequest(
    val system: String,
    val documentContext: String?,
    val history: List<Turn>,
    val instruction: String,
    val images: List<OracleImage>,
    val options: OracleOptions,
)

sealed interface OracleEvent {
    data class Delta(val text: String) : OracleEvent
    data class Usage(val promptTokens: Int, val completionTokens: Int, val cachedTokens: Int) : OracleEvent
}

class OracleException(val error: DomainError, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: error.toString(), cause)

class PageSnapshot(val page: ByteArray, val focus: ByteArray?)

// ---- state machine ----------------------------------------------------------------------------------------------

sealed interface ConvState {
    data object Idle : ConvState

    /** Pen is on the page. [utt] is null until the first stroke of a fresh utterance completes. */
    data class Writing(val utt: Utterance?) : ConvState
    data class Pausing(val utt: Utterance, val deadlineMillis: Long) : ConvState

    /** Listening is off: the utterance waits for [ConvEvent.AskNow]. */
    data class Held(val utt: Utterance) : ConvState
    data class Thinking(val utt: Utterance, val reqId: Int) : ConvState
    data class Scribing(
        val reqId: Int,
        val streamDone: Boolean = false,
        val scribeDone: Boolean = false,
        val streamFailed: Boolean = false,
    ) : ConvState

    data class Interrupted(val reqId: Int) : ConvState
    data class Failed(val utt: Utterance, val error: DomainError) : ConvState
}

/** The machine's whole memory: current state plus the last request id issued (ids only ever increase). */
data class ConvStatus(val state: ConvState = ConvState.Idle, val lastReqId: Int = 0)

data class ConvSettings(val pauseMillis: Long, val listening: Boolean)

sealed interface ConvEvent {
    data object PenDown : ConvEvent
    data class PenUp(val strokeId: StrokeId, val bounds: PageRect, val pageId: PageId, val atMillis: Long) : ConvEvent
    /** The pen lifted without leaving a stroke (an erase, a cancelled touch). */
    data class PenAborted(val atMillis: Long) : ConvEvent
    data class Tick(val atMillis: Long) : ConvEvent
    data object AskNow : ConvEvent
    data class OracleSilent(val reqId: Int) : ConvEvent
    data class OracleBlock(val reqId: Int, val block: Block) : ConvEvent
    data class OracleDone(val reqId: Int) : ConvEvent
    data class OracleFailed(val reqId: Int, val error: DomainError) : ConvEvent
    data object ScribeFinished : ConvEvent
    data object TapContinue : ConvEvent
    data object TapRetry : ConvEvent
}

sealed interface ConvEffect {
    data class StartTimer(val atMillis: Long) : ConvEffect
    data object CancelTimer : ConvEffect
    data class SendRequest(val utt: Utterance, val reqId: Int) : ConvEffect
    data class CancelRequest(val reqId: Int) : ConvEffect
    data class ShowInkwell(val utt: Utterance) : ConvEffect

    /** [dry] = the answer never came (fade the drop away instead of handing over to the pen). */
    data class HideInkwell(val dry: Boolean) : ConvEffect
    data class PlanBlock(val reqId: Int, val block: Block) : ConvEffect
    data class StopScribe(val reqId: Int) : ConvEffect
    data class ResumeScribe(val reqId: Int) : ConvEffect
    data class ShowFailure(val error: DomainError) : ConvEffect
    data object ClearFailure : ConvEffect

    /** Persist the exchange of [reqId] with [status] (the session adds heard text and markup). */
    data class Record(val reqId: Int, val status: ExchangeStatus) : ConvEffect
}
