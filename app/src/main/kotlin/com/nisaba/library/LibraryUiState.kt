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
    val isPrivate: Boolean = false,
    val pinned: Boolean = false,
    /** "edited 2 h ago" (for the trash: "deleted 3 days ago"). */
    val edited: String = "",
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
    data class Menu(val id: NotebookId, val name: String, val pinned: Boolean = false) : LibraryDialog

    /** What was deleted in the last 30 days: restore it or let it go. */
    data object Trash : LibraryDialog
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
    /** Which screenful of covers is shown (no scrolling on e-ink). */
    val gridPage: Int = 0,
    val trash: List<CoverItem> = emptyList(),
    /** The notebook written in most recently ("Continue"). */
    val latest: CoverItem? = null,
) {
    val gridPages: Int get() = maxOf(1, (notebooks.size + COVERS_PER_PAGE - 1) / COVERS_PER_PAGE)
    val visible: List<CoverItem> get() = notebooks.drop(gridPage.coerceIn(0, gridPages - 1) * COVERS_PER_PAGE).take(COVERS_PER_PAGE)

    companion object { const val COVERS_PER_PAGE = 6 }
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
private const val WEEK_DAYS = 7

/** "just now", "12 min ago", "3 h ago", "yesterday", "4 days ago", else the date. */
fun ago(thenMillis: Long, nowMillis: Long): String {
    val d = (nowMillis - thenMillis).coerceAtLeast(0)
    return when {
        d < MINUTE -> "just now"
        d < HOUR -> "${d / MINUTE} min ago"
        d < DAY -> "${d / HOUR} h ago"
        d < 2 * DAY -> "yesterday"
        d < WEEK_DAYS * DAY -> "${d / DAY} days ago"
        else -> java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)
            .format(java.time.Instant.ofEpochMilli(thenMillis).atZone(java.time.ZoneId.systemDefault()))
    }
}
