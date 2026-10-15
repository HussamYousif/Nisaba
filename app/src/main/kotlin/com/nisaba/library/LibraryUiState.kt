package com.nisaba.library

import com.nisaba.domain.notebook.AnswerStyle
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.shared.NotebookId

data class CoverItem(
    val id: NotebookId,
    val name: String,
    val pageCount: Int,
    val seed: Int,
    val hasDocument: Boolean,
    val endless: Boolean = false,
    val lesson: Boolean = false,
    val personaId: String = PersonaRegistry.DEFAULT_ID,
)

/** What "New" makes: the first thing it asks. */
enum class NotebookKind(val label: String, val hint: String) {
    Pages("Notebook", "Pages to write on, one after another"),
    Canvas("Blank canvas", "One endless page that pans in every direction"),
    Pdf("PDF", "Write on the pages of a PDF"),
    Lesson("Learn a PDF", "Nisaba teaches you a PDF, one idea at a time"),
}

sealed interface LibraryDialog {
    data object None : LibraryDialog

    /** "New", step 1: a notebook, a blank canvas, a PDF, or a PDF to learn. */
    data object ChooseKind : LibraryDialog

    /** "New", step 2: who answers in it ([personaId] = the voice picked so far). */
    data class ChooseVoice(val kind: NotebookKind, val personaId: String, val style: AnswerStyle = AnswerStyle()) : LibraryDialog

    /** "New", step 3 for a notebook or a canvas: its name. (A PDF goes to the system picker instead.) */
    data class NameIt(val kind: NotebookKind, val personaId: String, val style: AnswerStyle = AnswerStyle()) : LibraryDialog
    data class Menu(val id: NotebookId, val name: String) : LibraryDialog
    data class Rename(val id: NotebookId, val name: String) : LibraryDialog
    data class ConfirmDelete(val id: NotebookId, val name: String) : LibraryDialog
    data class Message(val text: String) : LibraryDialog

    /** A PDF was picked to learn from: ask what for (optional) before importing it. */
    data class LearnGoal(val uri: String, val personaId: String, val style: AnswerStyle = AnswerStyle()) : LibraryDialog
}

data class LibraryUiState(
    val notebooks: List<CoverItem> = emptyList(),
    val dialog: LibraryDialog = LibraryDialog.None,
    val loading: Boolean = true,
    /** 0..1 while a PDF is being imported, else null. */
    val importing: Float? = null,
)
