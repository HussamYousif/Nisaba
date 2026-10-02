package com.nisaba.domain.conversation

import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import kotlinx.coroutines.flow.Flow

/** The model. Cold flow: collecting sends the request, cancelling the collector aborts it. Errors are [OracleException]. */
interface Oracle {
    fun ask(request: OracleRequest): Flow<OracleEvent>
}

interface PageSnapshotter {
    /** Whole page (background + all ink) and, when [focus] is given, a crop around the new writing. */
    suspend fun snapshot(pageId: PageId, focus: PageRect?): PageSnapshot
}

interface ExchangeStore {
    suspend fun append(exchange: Exchange)
    suspend fun listByNotebook(id: NotebookId): List<Exchange>
    fun observeByNotebook(id: NotebookId): Flow<List<Exchange>>
}

/** Everything the session needs to make visible; implemented by the app (canvas, inkwell, toolbar messages). */
interface ConversationSurface {
    fun showInkwell(utt: Utterance)
    fun hideInkwell(dry: Boolean)
    fun planBlock(reqId: Int, block: com.nisaba.domain.answer.Block)
    fun stopScribe(reqId: Int)
    fun resumeScribe(reqId: Int)
    fun showFailure(error: com.nisaba.domain.shared.DomainError)
    fun clearFailure()
}

/** Extra context the session needs when it builds a request. */
interface ConversationContextPorts {
    suspend fun documentPages(): List<String>
    val currentDocumentPage: Int?
}
