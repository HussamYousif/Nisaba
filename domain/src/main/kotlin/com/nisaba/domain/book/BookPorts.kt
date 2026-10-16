package com.nisaba.domain.book

import com.nisaba.domain.shared.NotebookId
import kotlinx.coroutines.flow.Flow

/** A book notebook's map (made once, then marked as the walk goes on). */
interface BookStore {
    suspend fun get(notebookId: NotebookId): BookMap?
    fun observe(notebookId: NotebookId): Flow<BookMap?>
    suspend fun save(notebookId: NotebookId, map: BookMap)
}
