package com.nisaba.domain.notebook

import com.nisaba.domain.document.DocumentStore
import com.nisaba.domain.ink.InkStore
import com.nisaba.domain.shared.NotebookId

class DeleteNotebookUseCase(
    private val notebooks: NotebookStore,
    private val ink: InkStore,
    private val documents: DocumentStore,
) {
    /** Row first, files after: an orphan file is harmless, an orphan row is not. */
    suspend operator fun invoke(id: NotebookId) {
        val nb = notebooks.get(id) ?: return
        notebooks.delete(id)
        ink.deletePages(nb.pages.map { it.id })
        nb.notebook.documentId?.let { documents.delete(it) }
    }
}
