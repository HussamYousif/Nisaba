package com.nisaba.domain.conversation

import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import kotlinx.coroutines.flow.Flow

/** The model. Cold flow: collecting sends the request, cancelling the collector aborts it. Errors are [OracleException]. */
interface Oracle {
    fun ask(request: OracleRequest): Flow<OracleEvent>

    /** A question is probably coming (they are writing): open the connection now, so the request doesn't wait for it. */
    fun warmUp() = Unit
}

interface PageSnapshotter {
    /**
     * Whole page (background + all ink) and, when [focus] is given, a crop around the new writing. [fresh] = the new
     * writing's strokes: drawn dark, everything else faded, so the model can tell what is new.
     */
    suspend fun snapshot(
        pageId: PageId,
        focus: PageRect?,
        fresh: Set<com.nisaba.domain.shared.StrokeId> = emptySet(),
        labels: List<SnapshotLabel> = emptyList(),
    ): PageSnapshot
}

interface ExchangeStore {
    suspend fun append(exchange: Exchange)
    suspend fun listByNotebook(id: NotebookId): List<Exchange>
    fun observeByNotebook(id: NotebookId): Flow<List<Exchange>>
    suspend fun get(id: com.nisaba.domain.shared.ExchangeId): Exchange?

    /** Exchanges in any notebook whose question or answer contains [query] (case-insensitive), newest first. */
    suspend fun search(query: String, limit: Int): List<Exchange>

    /** Token counts of every exchange ever made. */
    suspend fun usage(): List<com.nisaba.domain.usage.ExchangeUsage>
}

/** Everything the session needs to make visible; implemented by the app (canvas, inkwell, toolbar messages). */
interface ConversationSurface {
    fun showInkwell(utt: Utterance)
    fun hideInkwell(dry: Boolean)
    /** [exchangeId] = the id the answer will be saved under, so its strokes can point at it. */
    fun planBlock(
        reqId: Int,
        block: com.nisaba.domain.answer.Block,
        exchangeId: com.nisaba.domain.shared.ExchangeId,
        personaId: String = com.nisaba.domain.persona.PersonaRegistry.DEFAULT_ID,
    )
    fun stopScribe(reqId: Int)
    fun resumeScribe(reqId: Int)
    fun showFailure(error: com.nisaba.domain.shared.DomainError)
    fun clearFailure()

    /** One line after a reply changed what Nisaba remembers ("Remembered: loved Dune"). */
    fun showMemoryNote(note: String) {}

    /** In a book notebook they asked to move on (the reply was `<next/>`): the walk's next page. */
    fun bookNext() {}

    /** The answer being written was cut short by the provider (too long, overloaded); what is written stays. */
    fun showCutShort() {}

    /** The reply asked for a reminder: offer to put it in the calendar (nothing happens without their tap). */
    fun offerReminder(reminder: Reminder) = Unit
}

/** Extra context the session needs when it builds a request. */
interface ConversationContextPorts {
    suspend fun documentPages(): List<String>
    val currentDocumentPage: Int?

    /** Non-null in a lesson notebook: the learner's goal (see [teachingPrompt]). */
    val teachGoal: String? get() = null

    /** The notebook's voice and answering habits, read per request (they can change while the notebook is open). */
    val persona: com.nisaba.domain.persona.Persona get() = com.nisaba.domain.persona.PersonaRegistry.default

    /** A private notebook: no memory is read into its requests or learned from its replies. */
    val isPrivate: Boolean get() = false

    /** Non-null in a book notebook once its map is made; [bookConcept] = the concept the open page belongs to. */
    val book: com.nisaba.domain.book.BookMap? get() = null
    val bookConcept: Int? get() = null

    /** Any voice by id, their own ones included (a second opinion); unknown → the default voice. */
    fun voice(id: String): com.nisaba.domain.persona.Persona = com.nisaba.domain.persona.PersonaRegistry.of(id)
    val style: com.nisaba.domain.notebook.AnswerStyle get() = com.nisaba.domain.notebook.AnswerStyle()
}
