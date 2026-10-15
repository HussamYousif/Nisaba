package com.nisaba.domain.search

import com.nisaba.domain.conversation.ExchangeStore
import com.nisaba.domain.notebook.NotebookStore
import com.nisaba.domain.notebook.NotebookWithPages
import com.nisaba.domain.shared.NotebookId
import kotlinx.coroutines.flow.first

/**
 * Questions and answers first (newest first), then notebook names, then the text of PDF pages; private notebooks and
 * the trash are never searched. At most [limit] hits.
 */
class SearchUseCase(
    private val exchanges: ExchangeStore,
    private val notebooks: NotebookStore,
    private val documents: com.nisaba.domain.document.DocumentTextSource? = null,
) {
    suspend operator fun invoke(query: String, limit: Int = DEFAULT_LIMIT): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val books = HashMap<NotebookId, NotebookWithPages?>()
        suspend fun book(id: NotebookId) = books.getOrPut(id) { notebooks.get(id) }
            ?.takeUnless { it.notebook.isPrivate || it.notebook.deletedAtMillis != null } // a private notebook is never found
        val hits = exchanges.search(query, limit).mapNotNull { e ->
            val nb = book(e.notebookId) ?: return@mapNotNull null
            val index = nb.pages.indexOfFirst { it.id == e.pageId }.takeIf { it >= 0 } ?: return@mapNotNull null
            SearchHit(
                nb.notebook.id, nb.notebook.name, e.pageId, index + 1,
                snippet(e.heard, query), snippet(plainText(e.answerMarkup), query), e.createdAtMillis, e.focus,
            )
        }.toMutableList()
        val summaries = notebooks.observeAll().first().filter { !it.isPrivate && it.deletedAtMillis == null }
        hits += summaries.filter { it.name.contains(query.trim(), ignoreCase = true) }.mapNotNull { s ->
            book(s.id)?.let { nb -> SearchHit(s.id, s.name, nb.pages.first().id, 1, "", "", s.updatedAtMillis, kind = HitKind.Title) }
        }
        documents?.let { docs -> hits += documentHits(docs, summaries.filter { it.hasDocument }, query, limit - hits.size, ::book) }
        return hits.take(limit)
    }

    private suspend fun documentHits(
        docs: com.nisaba.domain.document.DocumentTextSource,
        withDocs: List<com.nisaba.domain.notebook.NotebookSummary>,
        query: String,
        room: Int,
        book: suspend (NotebookId) -> NotebookWithPages?,
    ): List<SearchHit> {
        val out = ArrayList<SearchHit>()
        for (s in withDocs) {
            val nb = book(s.id)
            val doc = nb?.notebook?.documentId
            if (nb != null && doc != null) out += pageHits(docs.pages(doc), nb, s, query)
        }
        return out.take(room.coerceAtLeast(0))
    }

    /** The pages of a PDF whose text holds [query], as hits on the notebook's pages that show them. */
    private fun pageHits(
        texts: List<String>,
        nb: NotebookWithPages,
        s: com.nisaba.domain.notebook.NotebookSummary,
        query: String,
    ): List<SearchHit> = texts.withIndex().filter { it.value.contains(query.trim(), ignoreCase = true) }.mapNotNull { (i, text) ->
        com.nisaba.domain.notebook.indexOfDocumentPage(nb.pages, i + 1)?.let { at ->
            val words = snippet(text.replace(Regex("\\s+"), " "), query)
            SearchHit(s.id, s.name, nb.pages[at].id, at + 1, "p. ${i + 1}", words, s.updatedAtMillis, kind = HitKind.Document)
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 10
    }
}
