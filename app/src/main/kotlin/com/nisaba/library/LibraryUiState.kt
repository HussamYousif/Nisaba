package com.nisaba.library

import com.nisaba.domain.shared.NotebookId

data class CoverItem(
    val id: NotebookId,
    val name: String,
    val pageCount: Int,
    val seed: Int,
    val hasDocument: Boolean,
    val endless: Boolean = false,
)

sealed interface LibraryDialog {
    data object None : LibraryDialog
    data object Create : LibraryDialog
    data class Menu(val id: NotebookId, val name: String) : LibraryDialog
    data class Rename(val id: NotebookId, val name: String) : LibraryDialog
    data class ConfirmDelete(val id: NotebookId, val name: String) : LibraryDialog
    data class Message(val text: String) : LibraryDialog
}

data class LibraryUiState(
    val notebooks: List<CoverItem> = emptyList(),
    val dialog: LibraryDialog = LibraryDialog.None,
    val loading: Boolean = true,
    /** 0..1 while a PDF is being imported, else null. */
    val importing: Float? = null,
)
