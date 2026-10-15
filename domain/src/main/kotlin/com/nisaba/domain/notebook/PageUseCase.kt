package com.nisaba.domain.notebook

import com.nisaba.domain.ink.InkStore
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.IdGen
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.Outcome
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.StrokeId

/** Removes a page and its ink; a notebook keeps at least one page. */
class DeletePageUseCase(private val store: NotebookStore, private val ink: InkStore) {
    suspend operator fun invoke(notebookId: NotebookId, pageId: PageId): Outcome<Unit> {
        val pages = store.get(notebookId)?.pages ?: return Outcome.Err(DomainError.NotFound)
        if (pages.size <= 1 || pages.none { it.id == pageId }) return Outcome.Err(DomainError.Invalid("the only page"))
        store.deletePage(pageId)
        ink.deletePages(listOf(pageId))
        return Outcome.Ok(Unit)
    }
}

/** Moves a page one place earlier ([by] = -1) or later (+1) by swapping it with its neighbour. */
class MovePageUseCase(private val store: NotebookStore) {
    suspend operator fun invoke(notebookId: NotebookId, pageId: PageId, by: Int): Outcome<Unit> {
        val pages = store.get(notebookId)?.pages ?: return Outcome.Err(DomainError.NotFound)
        val i = pages.indexOfFirst { it.id == pageId }
        val j = i + by
        if (i < 0 || j !in pages.indices) return Outcome.Err(DomainError.Invalid("no room to move"))
        store.repositionPages(notebookId, listOf(pages[i].id to pages[j].position, pages[j].id to pages[i].position))
        return Outcome.Ok(Unit)
    }
}

/** A copy of a page right after it: same paper or document page, its ink copied with new ids. */
class DuplicatePageUseCase(private val insert: InsertPageUseCase, private val ink: InkStore, private val idGen: IdGen) {
    suspend operator fun invoke(notebookId: NotebookId, page: Page): Outcome<Page> {
        val copy = insert(notebookId, page.id, page.background, page.size)
        if (copy is Outcome.Ok) ink.save(copy.value.id, ink.load(page.id).strokes.map { it.copy(id = StrokeId(idGen.next())) })
        return copy
    }
}
