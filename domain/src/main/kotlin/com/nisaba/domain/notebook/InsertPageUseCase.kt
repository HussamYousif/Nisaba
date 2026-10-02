package com.nisaba.domain.notebook

import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.IdGen
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.Outcome
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageSize

class InsertPageUseCase(private val store: NotebookStore, private val idGen: IdGen) {
    /** Inserts after [afterPageId] (or at the end when null). Rebalances positions only when the gap collapses. */
    suspend operator fun invoke(
        notebookId: NotebookId,
        afterPageId: PageId?,
        background: PageBackground = PageBackground.Blank,
        size: PageSize = PageSize.PORTRAIT,
    ): Outcome<Page> {
        var pages = (store.get(notebookId) ?: return Outcome.Err(DomainError.NotFound)).pages
        var idx = if (afterPageId == null) pages.lastIndex else pages.indexOfFirst { it.id == afterPageId }
        if (afterPageId != null && idx < 0) return Outcome.Err(DomainError.NotFound)
        val next = pages.getOrNull(idx + 1)
        val prev = pages.getOrNull(idx)
        if (prev != null && next != null && gapTooSmall(prev.position, next.position)) {
            store.repositionPages(notebookId, rebalancedPositions(pages.map { it.id }))
            pages = store.get(notebookId)?.pages ?: return Outcome.Err(DomainError.NotFound)
            idx = pages.indexOfFirst { it.id == prev.id }
        }
        val pos = positionBetween(pages.getOrNull(idx)?.position, pages.getOrNull(idx + 1)?.position)
        val page = Page(PageId(idGen.next()), notebookId, pos, size, background)
        store.insertPage(page)
        return Outcome.Ok(page)
    }
}
