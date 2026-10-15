package com.nisaba.infra.db

import com.nisaba.domain.notebook.Notebook
import com.nisaba.domain.notebook.NotebookStore
import com.nisaba.domain.notebook.NotebookSummary
import com.nisaba.domain.notebook.NotebookWithPages
import com.nisaba.domain.notebook.Page
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class RoomNotebookStore(private val dao: NotebookDao) : NotebookStore {
    companion object {
        /** Opens the on-disk database; keeps Room types out of the app module. */
        fun open(context: android.content.Context) = RoomNotebookStore(NisabaDatabase.shared(context).notebooks())
    }

    override fun observeAll(): Flow<List<NotebookSummary>> = dao.observeSummaries().map { rows -> rows.map { it.toModel() } }

    override fun observe(id: NotebookId): Flow<NotebookWithPages?> =
        combine(dao.observeNotebook(id.value), dao.observePages(id.value)) { nb, pages ->
            nb?.let { NotebookWithPages(it.toModel(), pages.map(PageDto::toModel)) }
        }

    override suspend fun get(id: NotebookId): NotebookWithPages? =
        dao.notebook(id.value)?.let { NotebookWithPages(it.toModel(), dao.pages(id.value).map(PageDto::toModel)) }

    override suspend fun allNames(): List<String> = dao.names()

    override suspend fun create(notebook: Notebook, pages: List<Page>) = dao.createWithPages(notebook.toDto(), pages.map(Page::toDto))

    override suspend fun rename(id: NotebookId, name: String, updatedAtMillis: Long) = dao.rename(id.value, name, updatedAtMillis)

    override suspend fun delete(id: NotebookId) = dao.delete(id.value)

    override suspend fun insertPage(page: Page) = dao.insertPage(page.toDto())

    override suspend fun repositionPages(notebookId: NotebookId, positions: List<Pair<PageId, Double>>) =
        dao.reposition(positions.map { (id, pos) -> id.value to pos })

    override suspend fun setListening(id: NotebookId, listening: Boolean) = dao.setListening(id.value, listening)

    override suspend fun setPersona(id: NotebookId, personaId: String) = dao.setPersona(id.value, personaId)
}
