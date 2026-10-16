package com.nisaba.notebook

import com.nisaba.domain.book.BookStep
import com.nisaba.domain.book.conceptAt
import com.nisaba.domain.conversation.Purpose
import com.nisaba.domain.conversation.writeFresh
import com.nisaba.domain.shared.PageId
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// A book walk: Nisaba reads the attached book into a map of its core concepts (in the app's scope: minutes for a long
// book), then writes the walk a page at a time: the map of the book, one page per concept, the whole picture at the end.

/** Follows the walk's map as it is made and marked; starts (or resumes) the reading while there is no map yet. */
internal fun NotebookViewModel.followBook() {
    if (bookJob != null) return
    val id = deps.notebookId
    bookJob = ownScope.launch {
        launch { deps.sources.books.failure(id).collect { f -> mutableState.update { it.copy(bookFailure = f) } } }
        deps.sources.books.observe(id).collect { map ->
            if (map?.next != requestedStep) requestedStep = null // the page asked for was written: the next may be asked
            mutableState.update { it.copy(book = map) }
            if (map?.ready == true) maybeOpenWalk() else deps.sources.books.read(id)
        }
    }
}

/** The map is ready and the first page still blank: Nisaba writes the map of the book there. */
internal fun NotebookViewModel.maybeOpenWalk() {
    if (mutableState.value.book?.next != BookStep.Overview) return
    if (onBlankFirstPage()) nextBookStep()
}

private fun NotebookViewModel.onBlankFirstPage() = pageIndex == 0 && edit?.ink?.strokes.isNullOrEmpty()

/** "next" (written, or from the menu): the walk's next page. The map of the book goes on the blank first page. */
fun NotebookViewModel.nextBookStep() {
    val step = mutableState.value.book?.next ?: return
    if (step == requestedStep) return // already on its way
    val s = session ?: return
    requestedStep = step
    val page = currentPage()
    if (step == BookStep.Overview && page != null && onBlankFirstPage()) s.writeFresh(page.id, Purpose.Book(step))
    else writeOnFreshPage(Purpose.Book(step))
}

/** The reading stopped (no network, say): again, from where it stopped. */
fun NotebookViewModel.retryBook() = deps.sources.books.read(deps.notebookId)

fun NotebookViewModel.openBookMap() = mutableState.update { it.copy(bookOpen = true) }
fun NotebookViewModel.closeBookMap() = mutableState.update { it.copy(bookOpen = false) }

/** From the map: back to a page the walk wrote. */
fun NotebookViewModel.openBookPage(pageId: PageId) {
    closeBookMap()
    val i = notebook?.pages?.indexOfFirst { it.id == pageId } ?: return
    if (i >= 0) ownScope.launch { openPage(i, full = true) }
}

/** The concept the open page belongs to (for what they write there); null on the map's page. */
internal fun NotebookViewModel.bookConcept(): Int? {
    val map = mutableState.value.book ?: return null
    val page = currentPage()?.id ?: return null
    return conceptAt(map, notebook?.pages?.map { it.id }.orEmpty(), page)
}
