package com.nisaba.domain.document

import com.nisaba.domain.notebook.CreateNotebookUseCase
import com.nisaba.domain.notebook.Notebook
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.shared.Outcome

/**
 * Imports a document and opens a notebook on it: one notebook page per document page, or, with a [teachGoal], a
 * lesson notebook where Nisaba teaches the document on blank pages. Nisaba answers in the voice [personaId].
 */
class ImportDocumentUseCase(
    private val importer: DocumentImporter,
    private val createNotebook: CreateNotebookUseCase,
) {
    suspend operator fun invoke(
        source: String,
        teachGoal: String? = null,
        personaId: String = PersonaRegistry.DEFAULT_ID,
        style: com.nisaba.domain.notebook.AnswerStyle = com.nisaba.domain.notebook.AnswerStyle(),
        onProgress: (Float) -> Unit = {},
    ): Outcome<Notebook> =
        when (val imported = importer.import(source, onProgress)) {
            is Outcome.Err -> imported
            is Outcome.Ok -> {
                val doc = imported.value
                Outcome.Ok(createNotebook(doc.title, doc.id, doc.pageSizes, teachGoal = teachGoal, personaId = personaId, style = style))
            }
        }
}
