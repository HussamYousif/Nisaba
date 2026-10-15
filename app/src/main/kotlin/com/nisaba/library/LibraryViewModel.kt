package com.nisaba.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nisaba.domain.conversation.failureMessage
import com.nisaba.domain.document.ImportDocumentUseCase
import com.nisaba.domain.notebook.CreateNotebookUseCase
import com.nisaba.domain.notebook.DeleteNotebookUseCase
import com.nisaba.domain.notebook.ListNotebooksUseCase
import com.nisaba.domain.notebook.RenameNotebookUseCase
import com.nisaba.domain.notebook.AnswerStyle
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.settings.SettingsStore
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [settings] remembers the last voice picked, so "New" starts there (null in tests that don't care). */
class LibraryViewModel(
    private val list: ListNotebooksUseCase,
    private val create: CreateNotebookUseCase,
    private val rename: RenameNotebookUseCase,
    private val delete: DeleteNotebookUseCase,
    private val importDocument: ImportDocumentUseCase,
    private val settings: SettingsStore? = null,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()
    private var lastPersona = PersonaRegistry.DEFAULT_ID
    private var lastStyle = AnswerStyle()

    /** "New" sent the person to the system picker for this PDF (kind + voice), until it answers. */
    private var picking: LibraryDialog.ChooseVoice? = null

    init {
        scope.launch {
            list().collect { items ->
                _state.update { s ->
                    s.copy(
                        loading = false,
                        notebooks = items.map {
                            CoverItem(
                                it.id, it.name, it.pageCount, it.coverSeed, it.hasDocument, it.endless, it.lesson, it.personaId,
                                it.isPrivate,
                            )
                        },
                    )
                }
            }
        }
        settings?.let { s -> scope.launch { s.observe().collect { lastPersona = it.lastPersona; lastStyle = it.lastStyle } } }
    }

    fun onNew() = _state.update { it.copy(dialog = LibraryDialog.ChooseKind) }
    fun onMenu(item: CoverItem) = _state.update { it.copy(dialog = LibraryDialog.Menu(item.id, item.name)) }
    fun onRenameRequested(id: NotebookId, name: String) = _state.update { it.copy(dialog = LibraryDialog.Rename(id, name)) }
    fun onDeleteRequested(id: NotebookId, name: String) = _state.update { it.copy(dialog = LibraryDialog.ConfirmDelete(id, name)) }
    fun dismiss() = _state.update { it.copy(dialog = LibraryDialog.None) }

    // ---- "New": what kind → who answers → a name (or a PDF) ------------------------------------------------------

    fun chooseKind(kind: NotebookKind) = _state.update { it.copy(dialog = LibraryDialog.ChooseVoice(kind, lastPersona, lastStyle)) }

    fun selectStyle(style: AnswerStyle) = _state.update { s ->
        (s.dialog as? LibraryDialog.ChooseVoice)?.let { s.copy(dialog = it.copy(style = style)) } ?: s
    }

    fun selectVoice(personaId: String) = _state.update { s ->
        (s.dialog as? LibraryDialog.ChooseVoice)?.let { s.copy(dialog = it.copy(personaId = personaId)) } ?: s
    }

    /** Back from the voices to the kinds, or from the name to the voices. */
    fun back() = _state.update { s ->
        when (val d = s.dialog) {
            is LibraryDialog.ChooseVoice -> s.copy(dialog = LibraryDialog.ChooseKind)
            is LibraryDialog.NameIt -> s.copy(dialog = LibraryDialog.ChooseVoice(d.kind, d.personaId, d.style))
            else -> s
        }
    }

    /** The voice is picked: a notebook or canvas is named next; for a PDF, [pickPdf] opens the system picker. */
    fun confirmVoice(pickPdf: () -> Unit) {
        val d = _state.value.dialog as? LibraryDialog.ChooseVoice ?: return
        rememberVoice(d.personaId, d.style)
        when (d.kind) {
            NotebookKind.Pages, NotebookKind.Canvas ->
                _state.update { it.copy(dialog = LibraryDialog.NameIt(d.kind, d.personaId, d.style)) }
            NotebookKind.Pdf, NotebookKind.Lesson -> { picking = d; dismiss(); pickPdf() }
        }
    }

    /** The system picker answered "New" with [uri] (null = cancelled): a PDF opens; a PDF to learn asks for the goal first. */
    fun onPdfPicked(uri: String?, onCreated: (NotebookId) -> Unit) {
        val p = picking ?: return
        picking = null
        if (uri == null) return
        if (p.kind == NotebookKind.Lesson) {
            _state.update { it.copy(dialog = LibraryDialog.LearnGoal(uri, p.personaId, p.style)) }
        } else {
            importPdf(uri, teachGoal = null, personaId = p.personaId, style = p.style, onCreated = onCreated)
        }
    }

    /** Creates the notebook or canvas being named; [onCreated] receives its id (to open it). */
    fun confirmCreate(name: String, onCreated: (NotebookId) -> Unit) {
        val d = _state.value.dialog as? LibraryDialog.NameIt ?: return
        createNotebook(name, d.kind, d.personaId, d.style) { dismiss(); onCreated(it) }
    }

    /** A notebook or canvas named [name], answered in the voice [personaId]. */
    fun createNotebook(
        name: String,
        kind: NotebookKind,
        personaId: String,
        style: AnswerStyle = AnswerStyle(),
        onCreated: (NotebookId) -> Unit,
    ) {
        scope.launch { onCreated(create(name, endless = kind == NotebookKind.Canvas, personaId = personaId, style = style).id) }
    }

    /** Debug harness: removes the test notebooks whose names start with [prefix]. */
    fun debugDeleteNamed(prefix: String) {
        scope.launch { _state.value.notebooks.filter { it.name.startsWith(prefix) }.forEach { delete(it.id) } }
    }

    /** The goal for the PDF being learned is in ("" = all of it): import it as a lesson notebook. */
    fun confirmLearn(goal: String, onCreated: (NotebookId) -> Unit) {
        val d = _state.value.dialog as? LibraryDialog.LearnGoal ?: return
        importPdf(d.uri, teachGoal = goal, personaId = d.personaId, style = d.style, onCreated = onCreated)
    }

    private fun rememberVoice(personaId: String, style: AnswerStyle) {
        lastPersona = personaId; lastStyle = style
        settings?.let { s -> scope.launch { s.update { it.copy(lastPersona = personaId, lastStyle = style) } } }
    }

    /**
     * Imports the picked PDF (copy, measure, extract text) and hands its notebook to [onCreated]. With a [teachGoal]
     * ("" = no particular goal) it becomes a lesson notebook. Nisaba answers in it in the voice [personaId].
     */
    fun importPdf(
        uri: String,
        teachGoal: String? = null,
        personaId: String = lastPersona,
        style: AnswerStyle = lastStyle,
        onCreated: (NotebookId) -> Unit,
    ) {
        if (_state.value.importing != null) return
        _state.update { it.copy(importing = 0f, dialog = LibraryDialog.None) }
        scope.launch {
            val result = importDocument(uri, teachGoal, personaId, style) { p -> _state.update { it.copy(importing = p) } }
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
