package com.nisaba.domain.document

import com.nisaba.domain.notebook.CreateNotebookUseCase
import com.nisaba.domain.notebook.Notebook
import com.nisaba.domain.shared.Outcome

/** Imports a document and opens a notebook on it: one notebook page per document page. */
class ImportDocumentUseCase(
    private val importer: DocumentImporter,
    private val createNotebook: CreateNotebookUseCase,
) {
    suspend operator fun invoke(source: String, onProgress: (Float) -> Unit = {}): Outcome<Notebook> =
        when (val imported = importer.import(source, onProgress)) {
            is Outcome.Err -> imported
            is Outcome.Ok -> {
                val doc = imported.value
                Outcome.Ok(createNotebook(doc.title, doc.id, doc.pageSizes))
            }
        }
}
