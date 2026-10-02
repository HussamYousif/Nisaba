package com.nisaba.domain.notebook

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ListNotebooksUseCase(private val store: NotebookStore) {
    /** Most recently touched first. */
    operator fun invoke(): Flow<List<NotebookSummary>> = store.observeAll().map { list -> list.sortedByDescending { it.updatedAtMillis } }
}
