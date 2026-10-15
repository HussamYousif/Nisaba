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

    /** A private notebook keeps out of memory and search. */
    suspend fun setPrivate(id: NotebookId, isPrivate: Boolean)

    suspend fun setPinned(id: NotebookId, pinned: Boolean)

    /** Into the trash at [atMillis]; null takes it out again. */
    suspend fun setTrashed(id: NotebookId, atMillis: Long?)
    suspend fun setPaper(id: NotebookId, paper: Paper)

    /** Turns tutoring on ([goal] non-null, "" = all of it) or off. */
    suspend fun setTeachGoal(id: NotebookId, goal: String?)
    suspend fun deletePage(pageId: PageId)

    /** [AnswerMode.WhenAsked] is listening off; the eye in the bar toggles just that. */
    suspend fun setStyle(id: NotebookId, style: AnswerStyle)

    /** Something was written in the notebook at [atMillis] (the library lists the most recent first). */
    suspend fun touch(id: NotebookId, atMillis: Long)
}
