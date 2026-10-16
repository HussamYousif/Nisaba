package com.nisaba.notebook

import com.nisaba.domain.conversation.retryIfOffline
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nisaba.domain.conversation.ConversationSession
import com.nisaba.domain.ink.InkColor
import com.nisaba.domain.ink.InkEditSession
import com.nisaba.domain.ink.InkEditState
import com.nisaba.domain.ink.PageInk
import com.nisaba.domain.notebook.NotebookWithPages
import com.nisaba.domain.notebook.Page
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.settings.AnswerInk
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.Affine
import com.nisaba.domain.shared.Outcome
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageSize
import com.nisaba.domain.shared.fitTransform
import com.nisaba.scribe.layout.Occupancy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** [scopeOverride] lets tests run on a test scope; production uses [viewModelScope]. [startPage]: open there (search). */
class NotebookViewModel(
    internal val deps: NotebookDeps,
    scopeOverride: CoroutineScope? = null,
    internal val startPage: PageId? = null,
    /** A search hit: outlined on the page until the pen comes down. */
    internal val startMark: com.nisaba.domain.shared.PageRect? = null,
    internal val eraserRadius: Float = ERASER_RADIUS,
    internal val toolbarHideMillis: Long = TOOLBAR_HIDE_MILLIS,
    internal val toolbarUseMillis: Long = TOOLBAR_USE_MILLIS,
    internal val nowMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) : ViewModel() {
    internal val ownScope: CoroutineScope = scopeOverride ?: viewModelScope

    internal val mutableState = MutableStateFlow(NotebookUiState(aiAvailable = deps.sessions != null))
    val state: StateFlow<NotebookUiState> = mutableState.asStateFlow()
    internal val effectSink = MutableSharedFlow<NotebookEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<NotebookEffect> = effectSink
    internal val scribeSink = MutableSharedFlow<ScribeCommand>(extraBufferCapacity = 64)
    val scribeCommands: SharedFlow<ScribeCommand> = scribeSink

    internal var notebook: NotebookWithPages? = null
    internal var pageIndex = 0
    /** The open page's ink and its neighbours' (instant turns, undo kept), changed one edit at a time. */
    internal val inkEdit = InkEditSession(deps.ink)
    internal val edit: InkEditState? get() = inkEdit.open
    internal var affine = Affine(1f, 0f, 0f)
    internal var hideJob: Job? = null
    internal var memoryNoteJob: Job? = null
    internal var opened = false
    internal var settings = Settings()
    internal var session: ConversationSession? = null
    internal var answerScribe: AnswerScribeSession? = null
    internal var documentPageIndex: Int? = null
    internal var printedOccupancy: Occupancy? = null
    internal var printedJob: Job? = null
    internal var sessionGen = 0
    internal var turnsSinceFlash = 0
    internal var statusJob: Job? = null

    init {
        ownScope.launch { deps.settings.collect { s -> settings = s; onSettings(s) } }
        ownScope.launch { deps.sources.lessonTopics.collect { t -> mutableState.update { it.copy(lessonTopics = t) } } }
        ownScope.launch { deps.sources.online.collect { up -> if (up) session?.retryIfOffline() } }
        ownScope.launch { // a notice goes by itself after a while; the warning about unreadable ink stays until tapped
            mutableState.map { it.notice }.distinctUntilChanged().collectLatest { n ->
                if (n == null || n == SET_ASIDE_NOTICE) return@collectLatest
                kotlinx.coroutines.delay(NOTICE_MILLIS)
                mutableState.update { s -> if (s.notice == n) s.copy(notice = null) else s }
            }
        }
        ownScope.launch {
            deps.observe(deps.notebookId).collect { nb ->
                notebook = nb
                if (nb == null) return@collect
                if (!opened) {
                    opened = true
                    val target = startPage ?: deps.memory.lastPage(deps.notebookId) // search hit, else where it was left
                    openPage(nb.pages.indexOfFirst { it.id == target }.coerceAtLeast(0), full = false)
                    if (startPage == null) maybeDailyPage(nb) else mutableState.update { it.copy(highlight = startMark) }
                } else {
                    publish()
                }
                session?.updateSettings(settings, nb.notebook.listening)
            }
        }
    }

    internal fun onSettings(s: Settings) {
        session?.updateSettings(s, notebook?.notebook?.listening ?: true)
        mutableState.update {
            it.copy(
                answerColor = if (s.answerInk == AnswerInk.Black) InkColor.BLACK.argb else InkColor.LAPIS.argb,
                answerInk = s.answerInk, codeHighlight = s.codeHighlight, penInk = s.penInk, penSize = s.penSize,
                hintPending = !s.hintSeen,
            )
        }
    }

    /** Page-space top-left of the view on an endless canvas (null on paged notebooks). */
    internal var viewOrigin: PagePoint? = null

    internal var viewW = 0f
    internal var viewH = 0f

    fun onViewSize(w: Int, h: Int) {
        viewW = w.toFloat(); viewH = h.toFloat()
        val page = currentPage() ?: return
        affine = viewOrigin?.let { Affine(1f, -it.x, -it.y) } ?: fitTransform(page.size, w.toFloat(), h.toFloat())
    }

    /** The endless canvas was panned: pen points now map through the new offset, and the spot is remembered. */
    fun onPan(x: Float, y: Float) {
        viewOrigin = PagePoint(x, y)
        affine = Affine(1f, -x, -y)
        currentPage()?.let { deps.memory.saveView(it.id, PagePoint(x, y)) }
    }

    // ---- the endless canvas, from afar ---------------------------------------------------------------------------


    // ---- the pen: colour, thickness, shapes ---------------------------------------------------------------------


    /** +1 next page (appends a page at the end), −1 previous. */
    fun turnPage(delta: Int) {
        ownScope.launch {
            val nb = notebook ?: return@launch
            val target = pageIndex + delta
            if (target < 0) return@launch
            if (target >= nb.pages.size && !insertPageAfter(nb, nb.pages.lastOrNull()?.id)) return@launch
            openPage(target, full = true)
        }
    }

    /** Inserts a blank page after [after] and waits until the notebook shows it. */
    internal suspend fun insertPageAfter(nb: NotebookWithPages, after: PageId?): Boolean {
        if (deps.insertPage(deps.notebookId, after) !is Outcome.Ok) return false
        notebook = withTimeoutOrNull(PAGE_WAIT_MILLIS) {
            deps.observe(deps.notebookId).first { it != null && it.pages.size > nb.pages.size }
        } ?: return false
        return true
    }

    /** Inserts a blank page right after the current one and opens it (also how PDF notebooks get room to write). */
    fun insertBlankPage() {
        ownScope.launch {
            val nb = notebook ?: return@launch
            val current = nb.pages.getOrNull(pageIndex) ?: return@launch
            if (!insertPageAfter(nb, current.id)) return@launch
            openPage(pageIndex + 1, full = true)
        }
    }

    // ---- ink ----------------------------------------------------------------------------------------------------

    internal fun currentPage(): Page? = notebook?.pages?.getOrNull(pageIndex)

    /** [keepAnswer] = the page turns because a long answer spills over: the session and the hand carry on. */
    internal suspend fun openPage(index: Int, full: Boolean, keepAnswer: Boolean = false) {
        val nb = notebook ?: return
        val page = nb.pages.getOrNull(index) ?: return
        if (!keepAnswer) scribeSink.emit(ScribeCommand.Reset)
        pageIndex = index
        inkEdit.open(page.id) // turning back finds it as it was, undo history included
        preloadAround(nb, index)
        deps.memory.saveLastPage(deps.notebookId, page.id)
        val start = PagePoint(PageSize.ENDLESS_ORIGIN, PageSize.ENDLESS_ORIGIN)
        viewOrigin = if (page.size.isEndless) deps.memory.loadView(page.id) ?: start else null
        // every page fits the view its own way (a PDF page, a blank one): the pen must map through this page's fit
        affine = viewOrigin?.let { Affine(1f, -it.x, -it.y) } ?: if (viewW > 0f) fitTransform(page.size, viewW, viewH) else affine
        val setAside = edit?.ink?.setAside == true
        mutableState.update {
            it.copy(
                failure = null, resumeMark = if (keepAnswer) it.resumeMark else null, notice = if (setAside) SET_ASIDE_NOTICE else null,
                selection = null,
            )
        }
        printedJob?.cancel(); printedOccupancy = null
        if (!keepAnswer) {
            documentPageIndex = (page.background as? PageBackground.DocumentPage)?.index
            if (page.background is PageBackground.DocumentPage) {
                printedJob = ownScope.launch {
                    val printed = deps.sources.backgroundOccupancy(page)
                    if (currentPage()?.id == page.id) printedOccupancy = printed
                }
            }
            startSession(page)
        }
        publish()
        // a full-screen flash on opening and every few turns clears the ghosting; other turns get a clean pass
        effectSink.emit(NotebookEffect.Refresh(full && turnsSinceFlash++ % FLASH_EVERY == 0))
    }

    /** Loads the pages either side of [index] in the background and forgets the ones further away. */
    internal fun preloadAround(nb: NotebookWithPages, index: Int) {
        val keep = (index - 1..index + 1).mapNotNull { nb.pages.getOrNull(it)?.id }.toSet()
        ownScope.launch { inkEdit.preload(keep) { pageIndex == index } }
    }

    internal var hiddenAt = Long.MIN_VALUE / 2

    internal fun publish() {
        val nb = notebook ?: return
        val page = nb.pages.getOrNull(pageIndex) ?: return
        val e = edit ?: InkEditState(PageInk(page.id, emptyList()))
        mutableState.update {
            it.copy(
                title = nb.notebook.name, pageIndex = pageIndex, pageCount = nb.pages.size, pageSize = page.size, pageId = page.id,
                background = page.background, strokes = e.ink.strokes, canUndo = e.canUndo, canRedo = e.canRedo,
                listening = nb.notebook.listening, loading = false, endless = page.size.isEndless, viewOrigin = viewOrigin,
                lesson = nb.notebook.teachGoal != null, personaId = nb.notebook.personaId, style = nb.notebook.style,
                isPrivate = nb.notebook.isPrivate, pages = nb.pages, pageLabel = com.nisaba.domain.notebook.pageLabel(nb.pages, pageIndex),
                hasDocument = nb.notebook.documentId != null, paper = nb.notebook.paper,
            )
        }
        if (mutableState.value.hintPending && e.ink.strokes.isNotEmpty()) {
            mutableState.update { it.copy(hintPending = false) }
            ownScope.launch { deps.memory.markHintSeen() }
        }
    }

    override fun onCleared() { session?.close() }

    companion object {
        const val STROKE_WIDTH = 3.5f
        internal const val FLASH_EVERY = 5
        const val ERASER_RADIUS = 16f
        internal const val ANSWER_HIT = 40f
        internal const val PAGE_GRID_SIZE = PAGES_PER_SCREEN
        const val TOOLBAR_HIDE_MILLIS = 3000L
        const val TOOLBAR_USE_MILLIS = 5000L
        internal const val RESHOW_GUARD_MILLIS = 500L
        internal const val NANOS_PER_MILLI = 1_000_000L
        internal const val PAGE_WAIT_MILLIS = 3000L
        internal const val MEMORY_NOTE_MILLIS = 6000L
        internal const val NOTICE_MILLIS = 8000L
        const val CUT_SHORT_NOTICE = "The answer was cut short. Ask for the rest, or hold a finger on it: Ask again."
        const val SET_ASIDE_NOTICE = "Some ink on this page couldn't be read. Its file is kept aside, untouched."
        internal const val SPILL_SETTLE_MILLIS = 250L
        internal const val DEBUG_LEFT = 200f
        internal const val DEBUG_TOP = 320f
        internal const val DEBUG_GAP = 110f
        internal const val DEBUG_ROOM = 700f
        internal const val DEBUG_X_HEIGHT = 48f
        internal const val DEBUG_SEED = 99L
        internal const val DEBUG_STROKE_GAP = 25L
    }
}
