package com.nisaba.domain.notebook

import com.nisaba.domain.shared.DocumentId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageSize

data class Notebook(
    val id: NotebookId,
    val name: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val documentId: DocumentId?,
    val listening: Boolean,
    val coverSeed: Int,
    /** Non-null = a lesson notebook: Nisaba teaches the document; the text is the learner's goal ("" = all of it). */
    val teachGoal: String? = null,
)

sealed interface PageBackground {
    data object Blank : PageBackground
    data class DocumentPage(val documentId: DocumentId, val index: Int) : PageBackground
}

/** [position] is a fractional order key: insert between neighbours by taking the midpoint. */
data class Page(
    val id: PageId,
    val notebookId: NotebookId,
    val position: Double,
    val size: PageSize,
    val background: PageBackground,
)

data class NotebookSummary(
    val id: NotebookId,
    val name: String,
    val updatedAtMillis: Long,
    val pageCount: Int,
    val coverSeed: Int,
    val hasDocument: Boolean,
    val endless: Boolean = false,
    /** Nisaba teaches its document. */
    val lesson: Boolean = false,
)

data class NotebookWithPages(val notebook: Notebook, val pages: List<Page>)
