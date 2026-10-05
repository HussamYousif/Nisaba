package com.nisaba.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nisaba.domain.conversation.failureMessage
import com.nisaba.domain.document.ImportDocumentUseCase
import com.nisaba.domain.notebook.CreateNotebookUseCase
import com.nisaba.domain.notebook.DeleteNotebookUseCase
import com.nisaba.domain.notebook.ListNotebooksUseCase
import com.nisaba.domain.notebook.RenameNotebookUseCase
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val list: ListNotebooksUseCase,
    private val create: CreateNotebookUseCase,
    private val rename: RenameNotebookUseCase,
    private val delete: DeleteNotebookUseCase,
    private val importDocument: ImportDocumentUseCase,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        scope.launch {
            list().collect { items ->
                _state.update { s ->
                    s.copy(
                        loading = false,
                        notebooks = items.map {
                            CoverItem(it.id, it.name, it.pageCount, it.coverSeed, it.hasDocument, it.endless, it.lesson)
                        },
                    )
                }
            }
        }
    }

    fun onNew() = _state.update { it.copy(dialog = LibraryDialog.Create) }
    fun onMenu(item: CoverItem) = _state.update { it.copy(dialog = LibraryDialog.Menu(item.id, item.name)) }
    fun onRenameRequested(id: NotebookId, name: String) = _state.update { it.copy(dialog = LibraryDialog.Rename(id, name)) }
    fun onDeleteRequested(id: NotebookId, name: String) = _state.update { it.copy(dialog = LibraryDialog.ConfirmDelete(id, name)) }
    fun dismiss() = _state.update { it.copy(dialog = LibraryDialog.None) }

    /** Creates a notebook; [onCreated] receives its id (to open it). */
    fun confirmCreate(name: String, endless: Boolean, onCreated: (NotebookId) -> Unit) {
        scope.launch { val nb = create(name, endless = endless); dismiss(); onCreated(nb.id) }
    }

    /** A PDF was picked to learn from: ask for the (optional) goal first. */
    fun onPdfToLearnPicked(uri: String) = _state.update { it.copy(dialog = LibraryDialog.LearnGoal(uri)) }

    /**
     * Imports the picked PDF (copy, measure, extract text) and hands its notebook to [onCreated]. With a [teachGoal]
     * ("" = no particular goal) it becomes a lesson notebook.
     */
    fun importPdf(uri: String, teachGoal: String? = null, onCreated: (NotebookId) -> Unit) {
        if (_state.value.importing != null) return
        _state.update { it.copy(importing = 0f, dialog = LibraryDialog.None) }
        scope.launch {
            val result = importDocument(uri, teachGoal) { p -> _state.update { it.copy(importing = p) } }
            _state.update { it.copy(importing = null) }
            when (result) {
                is Outcome.Ok -> onCreated(result.value.id)
                is Outcome.Err -> _state.update { it.copy(dialog = LibraryDialog.Message(importMessage(result.error))) }
            }
        }
    }

    private fun importMessage(e: com.nisaba.domain.shared.DomainError) =
        if (e is com.nisaba.domain.shared.DomainError.Invalid) e.message else failureMessage(e)

    fun confirmRename(id: NotebookId, name: String) { scope.launch { rename(id, name); dismiss() } }
    fun confirmDelete(id: NotebookId) { scope.launch { delete(id); dismiss() } }
}
