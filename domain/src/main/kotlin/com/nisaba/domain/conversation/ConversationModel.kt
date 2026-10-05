package com.nisaba.domain.conversation

import com.nisaba.domain.answer.Block
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.StrokeId

/** Why Nisaba is about to write. Everything but [Writing] happens without new handwriting. */
sealed interface Purpose {
    /** The person wrote something: answer it. */
    data object Writing : Purpose

    /** A fresh lesson notebook: open the lesson. */
    data object Opening : Purpose

    /** The person came back to a lesson after a while: recap and re-check what was shaky. */
    data object Resume : Purpose

    /** They erased an answer and asked again: answer the same writing afresh. */
    data class Again(val previousHeard: String, val previousAnswer: String) : Purpose

    /** Write a clean copy of the notes on [sourcePageId] (on this, new, page). */
    data class Tidy(val sourcePageId: PageId) : Purpose
}

/**
 * Everything the user wrote on a page since the last exchange (one "utterance", however many pauses it spans), or,
 * for the other [Purpose]s, where Nisaba should write ([bounds]: the answer goes below it).
 */
data class Utterance(val pageId: PageId, val strokeIds: Set<StrokeId>, val bounds: PageRect, val purpose: Purpose = Purpose.Writing)

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
    /** Prompt tokens served from the provider's cache (part of [inputTokens]). */
    val cachedTokens: Int = 0,
    /** The writing that was answered (page space); null when Nisaba spoke first. Lets "ask again" re-send it. */
    val focus: PageRect? = null,
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
    /** What Nisaba knows about the person across notebooks (DESIGN §4.5); null when nothing yet. */
    val memoryContext: String? = null,
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
    /** [blocks] = answer blocks handed to the hand so far; the hand is done once it reports having drawn all of them. */
    data class Scribing(
        val reqId: Int,
        val streamDone: Boolean = false,
        val scribeDone: Boolean = false,
        val streamFailed: Boolean = false,
        val blocks: Int = 0,
    ) : ConvState

    data class Interrupted(val reqId: Int, val blocks: Int = 0) : ConvState
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

    /** Nisaba writes without new handwriting ([Utterance.purpose] says why); only from a quiet page. */
    data class Begin(val utt: Utterance) : ConvEvent
    data class OracleSilent(val reqId: Int) : ConvEvent
    data class OracleBlock(val reqId: Int, val block: Block) : ConvEvent
    data class OracleDone(val reqId: Int) : ConvEvent
    data class OracleFailed(val reqId: Int, val error: DomainError) : ConvEvent
    /** The hand drew everything it was given for [reqId], up to and including block number [blocksDrawn]. */
    data class ScribeFinished(val reqId: Int, val blocksDrawn: Int) : ConvEvent
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
