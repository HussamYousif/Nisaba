package com.nisaba.domain.notebook

import com.nisaba.domain.persona.PersonaRegistry
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
    /** The voice Nisaba answers in here (a [PersonaRegistry] id). */
    val personaId: String = PersonaRegistry.DEFAULT_ID,
    /** Plain notes are kept silent (transcribed for search); only what is addressed to Nisaba gets an answer. */
    val questionsOnly: Boolean = false,
    val answerLength: AnswerLength = AnswerLength.Normal,
) {
    val style: AnswerStyle get() = AnswerStyle(
        when {
            !listening -> AnswerMode.WhenAsked
            questionsOnly -> AnswerMode.QuestionsOnly
            else -> AnswerMode.Everything
        },
        answerLength,
    )
}

/** When Nisaba answers: everything written, only what is addressed to it, or only when asked (Ask now). */
enum class AnswerMode { Everything, QuestionsOnly, WhenAsked }

/** How long answers are by default, as a share of the voice's own length. */
enum class AnswerLength(val factor: Double) { Brief(0.6), Normal(1.0), Detailed(2.0) }

/** A notebook's answering habits, picked with its voice. */
data class AnswerStyle(val mode: AnswerMode = AnswerMode.Everything, val length: AnswerLength = AnswerLength.Normal)

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
    val personaId: String = PersonaRegistry.DEFAULT_ID,
)

data class NotebookWithPages(val notebook: Notebook, val pages: List<Page>)
