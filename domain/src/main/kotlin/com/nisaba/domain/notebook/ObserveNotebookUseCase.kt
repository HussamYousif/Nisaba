package com.nisaba.domain.notebook

import com.nisaba.domain.shared.NotebookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ObserveNotebookUseCase(private val store: NotebookStore) {
    /** Pages always sorted by position. */
    operator fun invoke(id: NotebookId): Flow<NotebookWithPages?> =
        store.observe(id).map { nb -> nb?.copy(pages = nb.pages.sortedBy { it.position }) }
}
