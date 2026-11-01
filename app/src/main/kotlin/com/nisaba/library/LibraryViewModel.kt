package com.nisaba.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nisaba.domain.conversation.failureMessage
import com.nisaba.domain.document.ImportDocumentUseCase
import com.nisaba.domain.notebook.CreateNotebookUseCase
import com.nisaba.domain.notebook.DeleteNotebookUseCase
import com.nisaba.domain.notebook.ListNotebooksUseCase
import com.nisaba.domain.notebook.RenameNotebookUseCase
import com.nisaba.domain.notebook.AnswerMode
import com.nisaba.domain.notebook.AnswerStyle
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.settings.SettingsStore
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pinning and the trash (null where a test doesn't care: delete is then for good). */
class LibraryChanges(
    val pin: com.nisaba.domain.notebook.PinNotebookUseCase? = null,
    val trash: com.nisaba.domain.notebook.TrashNotebookUseCase? = null,
    val restore: com.nisaba.domain.notebook.RestoreNotebookUseCase? = null,
    val emptyTrash: com.nisaba.domain.notebook.EmptyTrashUseCase? = null,
    val listTrash: com.nisaba.domain.notebook.ListTrashUseCase? = null,
    val now: () -> Long = System::currentTimeMillis,
    /** A book walk's map, for how far it has got ("3 of 9"). */
    val bookMap: suspend (NotebookId) -> com.nisaba.domain.book.BookMap? = { null },
)

/** How far a book walk has got, for under its cover. */
internal fun walkProgress(map: com.nisaba.domain.book.BookMap?): String = when {
    map == null || !map.ready -> "being read"
    map.synthesisPageId != null -> "walked through"
    else -> "${map.walked} of ${map.concepts.size}"
}

/** [settings] remembers the last voice picked, so "New" starts there (null in tests that don't care). */
class LibraryViewModel(
    private val list: ListNotebooksUseCase,
    private val create: CreateNotebookUseCase,
    private val rename: RenameNotebookUseCase,
    private val delete: DeleteNotebookUseCase,
    private val importDocument: ImportDocumentUseCase,
    private val settings: SettingsStore? = null,
    scopeOverride: CoroutineScope? = null,
    private val changes: LibraryChanges = LibraryChanges(),
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
                val now = changes.now()
                val walks = items.filter { it.book }.associate { it.id to walkProgress(changes.bookMap(it.id)) }
                val covers = items.map { it.toCover("edited " + ago(it.updatedAtMillis, now)).copy(progress = walks[it.id].orEmpty()) }
                _state.update { s ->
                    s.copy(
                        loading = false, notebooks = covers,
                        latest = items.maxByOrNull { it.updatedAtMillis }?.let { l -> covers.first { it.id == l.id } },
                        gridPage = s.gridPage.coerceIn(0, maxOf(0, (covers.size - 1) / LibraryUiState.COVERS_PER_PAGE)),
                    )
                }
            }
        }
        changes.listTrash?.let { trash ->
            scope.launch {
                trash().collect { items ->
                    val now = changes.now()
                    _state.update { s -> s.copy(trash = items.map { it.toCover("deleted " + ago(it.deletedAtMillis ?: now, now)) }) }
                }
            }
        }
        changes.emptyTrash?.let { empty -> scope.launch { empty() } } // what has been there 30 days goes for good
        settings?.let { s ->
            scope.launch { s.observe().collect { lastPersona = PersonaRegistry.normalize(it.lastPersona); lastStyle = it.lastStyle } }
        }
    }

    private fun com.nisaba.domain.notebook.NotebookSummary.toCover(edited: String) = CoverItem(
        id, name, pageCount, coverSeed, hasDocument, endless, lesson, personaId, isPrivate, pinned, edited, book,
    )

    /** The next or previous screenful of covers. */
    fun gridPage(delta: Int) = _state.update { s -> s.copy(gridPage = (s.gridPage + delta).coerceIn(0, s.gridPages - 1)) }

    fun pin(id: NotebookId, pinned: Boolean) { scope.launch { changes.pin?.invoke(id, pinned); dismiss() } }

    fun openTrash() = _state.update { it.copy(dialog = LibraryDialog.Trash) }
    fun restore(id: NotebookId) { scope.launch { changes.restore?.invoke(id) } }
    fun deleteForever(id: NotebookId) { scope.launch { delete(id) } }
    fun emptyTrash() { scope.launch { changes.emptyTrash?.invoke(all = true); dismiss() } }

    fun onNew() = _state.update { it.copy(dialog = LibraryDialog.ChooseKind) }
    fun onMenu(item: CoverItem) = _state.update { it.copy(dialog = LibraryDialog.Menu(item.id, item.name, item.pinned)) }
    fun onRenameRequested(id: NotebookId, name: String) = _state.update { it.copy(dialog = LibraryDialog.Rename(id, name)) }
    /** Delete puts it in the trash (restorable for 30 days); without a trash it asks first, then deletes for good. */
    fun onDeleteRequested(id: NotebookId, name: String) {
        val trash = changes.trash
        if (trash != null) {
            scope.launch { trash(id); dismiss() }
        } else {
            _state.update { it.copy(dialog = LibraryDialog.ConfirmDelete(id, name)) }
        }
    }
    fun dismiss() {
        if (_state.value.dialog is LibraryDialog.ChooseVoice) incoming = null // a PDF handed over and then turned down
        _state.update { it.copy(dialog = LibraryDialog.None) }
    }

    // ---- "New": what kind → who answers → a name (or a PDF) ------------------------------------------------------

    fun chooseKind(kind: NotebookKind) = _state.update { it.copy(dialog = LibraryDialog.ChooseVoice(kind, lastPersona, startingStyle())) }

    /**
     * The habits a new notebook's picker starts at: the last ones picked, but never muted. "When I ask" carried over
     * from one notebook made every later one (a lesson too) sit silent with nothing to say why.
     */
    private fun startingStyle(): AnswerStyle =
        if (lastStyle.mode == AnswerMode.WhenAsked) lastStyle.copy(mode = AnswerMode.Everything) else lastStyle

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

    /** A PDF another app handed over: who answers in it comes first, then it is imported. */
    private var incoming: String? = null

    fun offerIncomingPdf(uri: String) {
        incoming = uri
        _state.update { it.copy(dialog = LibraryDialog.ChooseVoice(NotebookKind.Pdf, lastPersona, startingStyle(), handed = true)) }
    }

    /**
     * The voice is picked: a notebook or canvas is named next; for a PDF, [pickPdf] opens the system picker (a PDF
     * handed over by another app is imported at once, [onCreated] opening it).
     */
    fun confirmVoice(onCreated: (NotebookId) -> Unit = {}, pickPdf: () -> Unit) {
        val d = _state.value.dialog as? LibraryDialog.ChooseVoice ?: return
        rememberVoice(d.personaId, d.style)
        val handed = incoming.also { incoming = null }
        when {
            handed != null && d.kind == NotebookKind.Pdf -> importPdf(handed, null, d.personaId, d.style, onCreated = onCreated)
            d.kind == NotebookKind.Pages || d.kind == NotebookKind.Canvas ->
                _state.update { it.copy(dialog = LibraryDialog.NameIt(d.kind, d.personaId, d.style)) }
            else -> { picking = d; dismiss(); pickPdf() }
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
            importPdf(uri, null, p.personaId, p.style, book = p.kind == NotebookKind.Book, onCreated = onCreated)
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

    /** Debug harness: removes the test notebooks whose names start with [prefix] (once the list has loaded). */
    fun debugDeleteNamed(prefix: String) {
        scope.launch { state.first { !it.loading }.notebooks.filter { it.name.startsWith(prefix) }.forEach { delete(it.id) } }
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
     * ("" = no particular goal) it becomes a lesson notebook; with [book], a walk through the book's concepts. Nisaba
     * answers in it in the voice [personaId].
     */
    fun importPdf(
        uri: String,
        teachGoal: String? = null,
        personaId: String = lastPersona,
        style: AnswerStyle = startingStyle(),
        book: Boolean = false,
        onCreated: (NotebookId) -> Unit,
    ) {
        if (_state.value.importing != null) return
        _state.update { it.copy(importing = 0f, dialog = LibraryDialog.None) }
        scope.launch {
            val result = importDocument(uri, teachGoal, personaId, style, book) { p -> _state.update { it.copy(importing = p) } }
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
