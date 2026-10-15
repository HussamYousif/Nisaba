package com.nisaba.domain.notebook

import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import kotlinx.coroutines.flow.Flow

interface NotebookStore {
    fun observeAll(): Flow<List<NotebookSummary>>
    fun observe(id: NotebookId): Flow<NotebookWithPages?>
    suspend fun get(id: NotebookId): NotebookWithPages?
    suspend fun allNames(): List<String>
    suspend fun create(notebook: Notebook, pages: List<Page>)
    suspend fun rename(id: NotebookId, name: String, updatedAtMillis: Long)
    suspend fun delete(id: NotebookId)
    suspend fun insertPage(page: Page)
    suspend fun repositionPages(notebookId: NotebookId, positions: List<Pair<PageId, Double>>)
    suspend fun setListening(id: NotebookId, listening: Boolean)
    suspend fun setPersona(id: NotebookId, personaId: String)
}
