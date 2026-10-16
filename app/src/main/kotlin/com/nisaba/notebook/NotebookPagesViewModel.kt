package com.nisaba.notebook

import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.notebook.Paper
import com.nisaba.domain.notebook.indexOfDocumentPage
import com.nisaba.domain.shared.Outcome
import com.nisaba.domain.shared.PageId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Pages (delete, move, duplicate, jump to a printed page), the paper, and tutoring a PDF.

/** A long press in the page grid picks page [index] for the actions under the grid (again: unpicks it). */
fun NotebookViewModel.selectGridPage(index: Int) =
    mutableState.update { it.copy(gridSelected = index.takeUnless { i -> i == it.gridSelected }) }

fun NotebookViewModel.deleteGridPage() = onGridPage { nb, page, i ->
    mutableState.update { it.copy(gridSelected = null) } // first: the grid must never ask for a page that just went
    if (deps.changes.pages.delete(nb.notebook.id, page.id) !is Outcome.Ok) return@onGridPage
    val after = awaitPages(nb.pages.size - 1) ?: return@onGridPage
    // the open page went: show its neighbour; another page went: the open one may have moved up
    val target = if (i == pageIndex) i.coerceAtMost(after.pages.lastIndex) else after.pages.indexOfFirst { it.id == currentPageId }
    openPage(target.coerceAtLeast(0), full = true)
}

fun NotebookViewModel.moveGridPage(by: Int) = onGridPage { nb, page, i ->
    if (deps.changes.pages.move(nb.notebook.id, page.id, by) !is Outcome.Ok) return@onGridPage
    val moved = awaitOrder(page.id, i + by) ?: return@onGridPage
    notebook = moved
    pageIndex = moved.pages.indexOfFirst { it.id == currentPageId }.coerceAtLeast(0)
    mutableState.update { it.copy(gridSelected = i + by) }
    publish()
}

fun NotebookViewModel.duplicateGridPage() = onGridPage { nb, page, _ ->
    deps.changes.pages.duplicate(nb.notebook.id, page)
    awaitPages(nb.pages.size + 1)?.let { notebook = it; pageIndex = it.pages.indexOfFirst { p -> p.id == currentPageId }.coerceAtLeast(0) }
    mutableState.update { it.copy(gridSelected = null) }
    publish()
}

/** Go to p. [typed] (as printed in the PDF). */
fun NotebookViewModel.goToPrintedPage(typed: String) {
    val n = typed.toIntOrNull() ?: return
    val i = indexOfDocumentPage(notebook?.pages ?: return, n)
    if (i == null) { mutableState.update { it.copy(notice = "This PDF has no p. $n.") }; return }
    goToPage(i)
}

fun NotebookViewModel.openPaper() = mutableState.update { it.copy(paperOpen = true, menuOpen = false) }
fun NotebookViewModel.closePaper() = mutableState.update { it.copy(paperOpen = false) }

fun NotebookViewModel.setPaper(paper: Paper) {
    ownScope.launch { deps.changes.pages.setPaper(deps.notebookId, paper) }
    closePaper()
}

/** "Tutor me on this PDF" on a PDF notebook, or "Stop tutoring". */
fun NotebookViewModel.toggleTutor() {
    val tutoring = notebook?.notebook?.teachGoal != null
    ownScope.launch {
        deps.changes.pages.setTeachGoal(deps.notebookId, if (tutoring) null else "")
        mutableState.update { it.copy(notice = if (tutoring) TUTOR_OFF else TUTOR_ON) }
    }
}

/**
 * After an answer is erased: the pages it had spilled onto that are now empty (blank paper, no ink) go too, except the
 * open page and a notebook's last one.
 */
internal suspend fun NotebookViewModel.dropEmptyPages(candidates: List<PageId>) {
    val nb = notebook ?: return
    val blank = nb.pages.filter { it.id in candidates && it.id != currentPageId && it.background == PageBackground.Blank }
    for (page in blank) if (deps.ink.load(page.id).ink.strokes.isEmpty()) deps.changes.pages.delete(deps.notebookId, page.id)
}

private val NotebookViewModel.currentPageId: PageId? get() = currentPage()?.id

private fun NotebookViewModel.onGridPage(
    action: suspend (com.nisaba.domain.notebook.NotebookWithPages, com.nisaba.domain.notebook.Page, Int) -> Unit,
) {
    val i = mutableState.value.gridSelected ?: return
    val nb = notebook ?: return
    val page = nb.pages.getOrNull(i) ?: return
    ownScope.launch { action(nb, page, i) }
}

private suspend fun NotebookViewModel.awaitPages(count: Int) = withTimeoutOrNull(PAGE_WAIT) {
    deps.observe(deps.notebookId).first { it != null && it.pages.size == count }
}

private suspend fun NotebookViewModel.awaitOrder(id: PageId, at: Int) = withTimeoutOrNull(PAGE_WAIT) {
    deps.observe(deps.notebookId).first { it != null && it.pages.getOrNull(at)?.id == id }
}

private const val PAGE_WAIT = 3000L
internal const val TUTOR_ON = "Tutoring: Nisaba teaches this PDF from now on. Write anything to begin."
internal const val TUTOR_OFF = "No longer tutoring: Nisaba just answers here again."

/** ⋯ → Export as PDF / as text: the screen asks where; [exportTo] writes it. */
fun NotebookViewModel.export(asPdf: Boolean) {
    val name = notebook?.notebook?.name ?: return
    effectSink.tryEmit(NotebookEffect.Export(asPdf, name + if (asPdf) ".pdf" else ".md"))
}

fun NotebookViewModel.exportTo(asPdf: Boolean, uri: String?) {
    if (uri == null) return
    mutableState.update { it.copy(notice = "Exporting…") } // a long PDF takes a while: the page says it's on it
    ownScope.launch {
        val ok = deps.sources.export(asPdf, uri)
        mutableState.update { it.copy(notice = if (ok) "Exported." else "Couldn't export the notebook.") }
    }
}
