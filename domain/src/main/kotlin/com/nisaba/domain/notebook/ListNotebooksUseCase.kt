package com.nisaba.domain.notebook

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ListNotebooksUseCase(private val store: NotebookStore) {
    /** Pinned ones first, then the most recently touched; the trash is left out. */
    operator fun invoke(): Flow<List<NotebookSummary>> = store.observeAll().map { list ->
        val order = compareByDescending<NotebookSummary> { it.pinned }.thenByDescending { it.updatedAtMillis }
        list.filter { it.deletedAtMillis == null }.sortedWith(order)
    }
}
