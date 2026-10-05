package com.nisaba.domain.search

import com.nisaba.domain.conversation.ExchangeStore
import com.nisaba.domain.notebook.NotebookStore
import com.nisaba.domain.notebook.NotebookWithPages
import com.nisaba.domain.shared.NotebookId

/** Searches every notebook's questions and answers; newest first, at most [limit]. Hits on deleted pages are dropped. */
class SearchUseCase(private val exchanges: ExchangeStore, private val notebooks: NotebookStore) {
    suspend operator fun invoke(query: String, limit: Int = DEFAULT_LIMIT): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val books = HashMap<NotebookId, NotebookWithPages?>()
        return exchanges.search(query, limit).mapNotNull { e ->
            val nb = books.getOrPut(e.notebookId) { notebooks.get(e.notebookId) } ?: return@mapNotNull null
            val index = nb.pages.indexOfFirst { it.id == e.pageId }.takeIf { it >= 0 } ?: return@mapNotNull null
            SearchHit(
                nb.notebook.id, nb.notebook.name, e.pageId, index + 1,
                snippet(e.heard, query), snippet(plainText(e.answerMarkup), query), e.createdAtMillis,
            )
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 10
    }
}
