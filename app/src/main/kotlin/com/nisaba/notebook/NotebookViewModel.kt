package com.nisaba.notebook

import com.nisaba.domain.shared.PageSize
import com.nisaba.domain.shared.PageId
import androidx.lifecycle.ViewModel
import com.nisaba.canvas.BlockRef
import com.nisaba.canvas.answerPen
import androidx.lifecycle.viewModelScope
import com.nisaba.device.PenEvent
import com.nisaba.device.PenTool
import com.nisaba.domain.conversation.ConversationContextPorts
import com.nisaba.domain.conversation.ConversationSession
import com.nisaba.domain.conversation.ConversationSurface
import com.nisaba.domain.conversation.ConvState
import com.nisaba.domain.conversation.failureMessage
import com.nisaba.domain.ink.CommitStrokeUseCase
import com.nisaba.domain.ink.CommitStrokesUseCase
import com.nisaba.domain.ink.EraseMode
import com.nisaba.domain.ink.EraseAnswerUseCase
import com.nisaba.domain.ink.EraseUseCase
import com.nisaba.domain.ink.answerAt
import com.nisaba.domain.lesson.LessonTopic
import com.nisaba.domain.conversation.Exchange
import com.nisaba.domain.ink.InkColor
import com.nisaba.domain.ink.InkEditState
import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.ink.LoadPageInkUseCase
import com.nisaba.domain.ink.PageInk
import com.nisaba.domain.ink.RedoUseCase
import com.nisaba.domain.ink.ShapeKind
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.ink.StrokeOrigin
import com.nisaba.domain.ink.UndoUseCase
import com.nisaba.domain.ink.recognizeShape
import com.nisaba.domain.ink.shapeStroke
import com.nisaba.domain.notebook.InsertPageUseCase
import com.nisaba.domain.notebook.NotebookWithPages
import com.nisaba.domain.notebook.ObserveNotebookUseCase
import com.nisaba.domain.notebook.Page
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.notebook.RenameNotebookUseCase
import com.nisaba.domain.notebook.SetListeningUseCase
import com.nisaba.domain.notebook.SetPersonaUseCase
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.settings.AnswerInk
import com.nisaba.domain.settings.HandStyle
import com.nisaba.domain.settings.PenSize
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.Affine
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.IdGen
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.Outcome
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.StrokeId
import com.nisaba.domain.shared.fitTransform
import com.nisaba.domain.shared.toPage
import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.settings.HandSpeed
import com.nisaba.scribe.hand.GlyphSet
import com.nisaba.scribe.hand.HumanParams
import com.nisaba.scribe.layout.LayoutRegion
import com.nisaba.scribe.layout.Occupancy
import com.nisaba.scribe.layout.OccupancyLookup
import com.nisaba.scribe.motion.ScribePlanUseCase
import com.nisaba.scribe.motion.speedProfile
import com.nisaba.scribe.motion.TimedStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Builds the ask loop for one page; supplied by the composition root (null in plain-ink tests). */
fun interface SessionFactory {
    fun create(
        surface: ConversationSurface,
        scope: CoroutineScope,
        settings: Settings,
        listening: Boolean,
        context: ConversationContextPorts,
    ): ConversationSession
}

/** The ink editing actions a notebook page needs. */
class InkUseCases(
    val load: LoadPageInkUseCase,
    val commit: CommitStrokeUseCase,
    val commitMany: CommitStrokesUseCase,
    val erase: EraseUseCase,
    val undo: UndoUseCase,
    val redo: RedoUseCase,
    val eraseAnswer: EraseAnswerUseCase,
)

class NotebookDeps(
    val notebookId: NotebookId,
    val observe: ObserveNotebookUseCase,
    val insertPage: InsertPageUseCase,
    val rename: RenameNotebookUseCase,
    val ink: InkUseCases,
    val setListening: SetListeningUseCase,
    val idGen: IdGen,
    val settings: Flow<Settings> = emptyFlow(),
    /** Where the view of an endless canvas was last left (page space), and the way to remember it. */
    val memory: NotebookMemory = NotebookMemory(),
    val sessions: SessionFactory? = null,
    val hands: (HandStyle) -> GlyphSet = { error("no hand configured") },
    val sources: NotebookSources = NotebookSources(),
    /** Changes the voice Nisaba answers in here (null: the voice can't be changed, e.g. in plain-ink tests). */
    val setPersona: SetPersonaUseCase? = null,
)

/** What answers draw on besides the ink: the document, its printed text, saved exchanges. */
class NotebookSources(
    /** Text of the notebook's document, one string per page (empty for plain notebooks). */
    val documentPages: suspend () -> List<String> = { emptyList() },
    /** Occupancy of what is printed on a document page, so answers avoid it (null for blank pages). */
    val backgroundOccupancy: suspend (Page) -> Occupancy? = { null },
    /** A saved exchange (to ask it again). */
    val exchange: suspend (ExchangeId) -> Exchange? = { null },
    /** A lesson notebook's progress as the tutor records it. */
    val lessonTopics: Flow<List<LessonTopic>> = emptyFlow(),
)

/** What the app remembers for the user between visits: the first-run hint, where each endless canvas was left, the pen. */
class NotebookMemory(
    val markHintSeen: suspend () -> Unit = {},
    val loadView: (PageId) -> PagePoint? = { null },
    val saveView: (PageId, PagePoint) -> Unit = { _, _ -> },
    val savePen: suspend (ink: Int, size: PenSize) -> Unit = { _, _ -> },
)

/** [scopeOverride] lets tests run on a test scope; production uses [viewModelScope]. [startPage]: open there (search). */
class NotebookViewModel(
    private val deps: NotebookDeps,
    scopeOverride: CoroutineScope? = null,
    private val startPage: PageId? = null,
    private val eraserRadius: Float = ERASER_RADIUS,
    private val toolbarHideMillis: Long = TOOLBAR_HIDE_MILLIS,
    private val toolbarUseMillis: Long = TOOLBAR_USE_MILLIS,
    private val nowMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) : ViewModel() {
    private val ownScope: CoroutineScope = scopeOverride ?: viewModelScope

    private val _state = MutableStateFlow(NotebookUiState(aiAvailable = deps.sessions != null))
    val state: StateFlow<NotebookUiState> = _state.asStateFlow()
    private val _effects = MutableSharedFlow<NotebookEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<NotebookEffect> = _effects
    private val _scribe = MutableSharedFlow<ScribeCommand>(extraBufferCapacity = 64)
    val scribeCommands: SharedFlow<ScribeCommand> = _scribe

    private var notebook: NotebookWithPages? = null
    private var pageIndex = 0
    private var edit: InkEditState? = null
    private val editLock = Mutex() // ink changes run one at a time, each against the page it was made on

    /** Pages next to the open one, already loaded (with their undo history) so a turn is instant. Guarded by [editLock]. */
    private val nearby = HashMap<PageId, InkEditState>()
    private var affine = Affine(1f, 0f, 0f)
    private var hideJob: Job? = null
    private var memoryNoteJob: Job? = null
    private var opened = false
    private var settings = Settings()
    private var session: ConversationSession? = null
    private var answerScribe: AnswerScribeSession? = null
    private var documentPageIndex: Int? = null
    private var printedOccupancy: Occupancy? = null
    private var printedJob: Job? = null
    private var sessionGen = 0
    private var statusJob: Job? = null

    init {
        ownScope.launch { deps.settings.collect { s -> settings = s; onSettings(s) } }
        ownScope.launch { deps.sources.lessonTopics.collect { t -> _state.update { it.copy(lessonTopics = t) } } }
        ownScope.launch {
            deps.observe(deps.notebookId).collect { nb ->
                notebook = nb
                if (nb == null) return@collect
                if (!opened) {
                    opened = true
                    openPage(nb.pages.indexOfFirst { it.id == startPage }.coerceAtLeast(0), full = false)
                } else {
                    publish()
                }
                session?.updateSettings(settings, nb.notebook.listening)
            }
        }
    }

    private fun onSettings(s: Settings) {
        session?.updateSettings(s, notebook?.notebook?.listening ?: true)
        _state.update {
            it.copy(
                answerColor = if (s.answerInk == AnswerInk.Black) InkColor.BLACK.argb else InkColor.LAPIS.argb,
                answerInk = s.answerInk, codeHighlight = s.codeHighlight, penInk = s.penInk, penSize = s.penSize,
                hintPending = !s.hintSeen,
            )
        }
    }

    /** Page-space top-left of the view on an endless canvas (null on paged notebooks). */
    private var viewOrigin: PagePoint? = null

    private var viewW = 0f
    private var viewH = 0f

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

    fun onPenEvent(e: PenEvent) {
        when (e) {
            is PenEvent.PenDown -> { session?.onPenDown(); hideToolbarLater() }
            is PenEvent.StrokeDone -> {
                val page = currentPage()?.id ?: return
                val points = toPagePoints(e.points) // mapped now: the view may pan or turn before the commit runs
                val tool = _state.value.tool
                when {
                    tool.erases -> onPenEvent(PenEvent.EraseDone(List(e.points.size) { PagePoint(e.points.xs[it], e.points.ys[it]) }))
                    tool == PenTool.Shape -> ownScope.launch { commitShape(page, points) }
                    else -> ownScope.launch { commitStroke(page, points) }
                }
            }
            is PenEvent.EraseDone -> {
                val page = currentPage()?.id ?: return
                val path = e.path.map { affine.toPage(it.x, it.y) }
                val mode = if (_state.value.tool == PenTool.AreaEraser) EraseMode.Area else EraseMode.Stroke
                ownScope.launch { erase(page, mode, path); session?.onPenAborted() }
            }
        }
    }

    // ---- the endless canvas, from afar ---------------------------------------------------------------------------

    fun openPageGrid() {
        hideJob?.cancel()
        _state.update { it.copy(pageGridOpen = true, pageGridScreen = pageIndex / PAGE_GRID_SIZE) }
    }
    fun closePageGrid() { _state.update { it.copy(pageGridOpen = false) }; hideToolbarLater(toolbarUseMillis) }
    fun pageGridScreen(n: Int) = _state.update { it.copy(pageGridScreen = n.coerceAtLeast(0)) }

    /** Opens page [index] (0-based) from the grid. */
    fun goToPage(index: Int) {
        closePageGrid()
        if (index == pageIndex) return
        ownScope.launch { openPage(index, full = true) }
    }

    fun openMenu() { hideJob?.cancel(); _state.update { it.copy(menuOpen = true) } }
    fun closeMenu() { _state.update { it.copy(menuOpen = false) }; hideToolbarLater(toolbarUseMillis) }

    fun openVoice() = _state.update { it.copy(voiceOpen = true) }
    fun closeVoice() = _state.update { it.copy(voiceOpen = false) }

    /** From the next reply on, Nisaba answers in this notebook in the voice [personaId]. */
    fun setVoice(personaId: String) {
        closeVoice()
        val set = deps.setPersona ?: return
        ownScope.launch { set(deps.notebookId, personaId) }
    }

    fun showProgress() = _state.update { it.copy(progressOpen = true) }
    fun closeProgress() = _state.update { it.copy(progressOpen = false) }

    fun showOverview() = _state.update { it.copy(overview = true) }
    fun closeOverview() = _state.update { it.copy(overview = false) }

    /** What the view shows now (page space), for the overview's outline. */
    fun visibleArea(): PageRect {
        val o = viewOrigin ?: PagePoint(0f, 0f)
        return PageRect(o.x, o.y, o.x + viewW, o.y + viewH)
    }

    /** Centres the endless canvas on [p] (a spot picked in the overview). */
    fun jumpTo(p: PagePoint) = goTo(PagePoint(p.x - viewW / 2f, p.y - viewH / 2f))

    /** Back to where the canvas starts. */
    fun goHome() = goTo(PagePoint(PageSize.ENDLESS_ORIGIN, PageSize.ENDLESS_ORIGIN))

    private fun goTo(origin: PagePoint) {
        closeOverview()
        ownScope.launch { _effects.emit(NotebookEffect.PanTo(origin)); _effects.emit(NotebookEffect.Refresh(full = true)) }
    }

    fun setTool(tool: PenTool) { _state.update { it.copy(tool = tool) }; usedToolbar() }

    // ---- the pen: colour, thickness, shapes ---------------------------------------------------------------------

    /** The pen button: picks the pen, or (when it already is) opens its colours and sizes. */
    fun tapPen() = if (_state.value.tool == PenTool.Pen) openPanel(ToolPanel.Pen) else setTool(PenTool.Pen)

    /** The shape button: picks the shape tool, or (when it already is) opens the shapes. */
    fun tapShape() = if (_state.value.tool == PenTool.Shape) openPanel(ToolPanel.Shapes) else setTool(PenTool.Shape)

    private fun openPanel(panel: ToolPanel) { hideJob?.cancel(); _state.update { it.copy(toolPanel = panel) } }
    fun closePanel() { _state.update { it.copy(toolPanel = null) }; hideToolbarLater(toolbarUseMillis) }

    fun setPenInk(argb: Int) = savePen(argb, _state.value.penSize)
    fun setPenSize(size: PenSize) = savePen(_state.value.penInk, size)

    private fun savePen(ink: Int, size: PenSize) {
        _state.update { it.copy(penInk = ink, penSize = size) }
        ownScope.launch { deps.memory.savePen(ink, size) }
    }

    /** Picks [kind] and closes the panel; the shape tool stays selected. */
    fun setShape(kind: ShapeKind) { _state.update { it.copy(shape = kind, tool = PenTool.Shape) }; closePanel() }

    fun undo() = ownScope.launch {
        usedToolbar()
        val page = currentPage()?.id ?: return@launch
        if (editOpenPage(page) { if (it.canUndo) deps.ink.undo(it) else null }) _effects.emit(NotebookEffect.Refresh(full = false))
    }

    fun redo() = ownScope.launch {
        usedToolbar()
        val page = currentPage()?.id ?: return@launch
        if (editOpenPage(page) { if (it.canRedo) deps.ink.redo(it) else null }) _effects.emit(NotebookEffect.Refresh(full = false))
    }

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
    private suspend fun insertPageAfter(nb: NotebookWithPages, after: PageId?): Boolean {
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

    /** A tap brings the bar back, unless it only just hid (a tap racing the hide would make it flash). */
    fun showToolbar() {
        if (!_state.value.toolbarVisible && nowMillis() - hiddenAt < RESHOW_GUARD_MILLIS) return
        _state.update { it.copy(toolbarVisible = true) }
        hideToolbarLater(toolbarUseMillis)
    }

    fun beginRename() = _state.update { it.copy(renaming = true) }
    fun cancelRename() = _state.update { it.copy(renaming = false) }
    fun confirmRename(name: String) {
        ownScope.launch { deps.rename(deps.notebookId, name); _state.update { it.copy(renaming = false) } }
    }

    // ---- the ask loop -------------------------------------------------------------------------------------------

    fun toggleListening() {
        usedToolbar()
        val nb = notebook?.notebook ?: return
        ownScope.launch { deps.setListening(nb.id, !nb.listening) }
    }

    fun askNow() { session?.onAskNow() }

    /** A clean, structured copy of this page's notes, written on a new page right after it (the original stays). */
    fun tidyPage() {
        val nb = notebook ?: return
        val source = currentPage() ?: return
        if (source.size.isEndless || deps.sessions == null) return
        ownScope.launch {
            if (!insertPageAfter(nb, source.id)) return@launch
            openPage(pageIndex + 1, full = true)
            currentPage()?.let { session?.tidy(source.id, it.id) }
        }
    }

    // ---- one answer: erase it, ask again ------------------------------------------------------------------------

    /** A finger held on the page: if it is on an answer, offer what can be done with it. */
    fun onLongPress(viewX: Float, viewY: Float) {
        val page = currentPage() ?: return
        val ex = answerAt(edit?.ink?.strokes.orEmpty(), affine.toPage(viewX, viewY), ANSWER_HIT) ?: return
        ownScope.launch {
            val saved = deps.sources.exchange(ex)
            val again = deps.sessions != null && saved?.focus != null && saved.pageId == page.id
            _state.update { it.copy(answerMenu = AnswerMenu(ex, canAskAgain = again)) }
        }
    }

    fun dismissAnswerMenu() = _state.update { it.copy(answerMenu = null) }

    fun eraseAnswer(ex: ExchangeId) {
        dismissAnswerMenu()
        ownScope.launch { eraseAnswerEverywhere(ex); _effects.emit(NotebookEffect.Refresh(full = false)) }
    }

    /** Erases the answer and asks the same writing again (only offered on the page it was asked on). */
    fun askAgain(ex: ExchangeId) {
        dismissAnswerMenu()
        ownScope.launch {
            val saved = deps.sources.exchange(ex) ?: return@launch
            eraseAnswerEverywhere(ex)
            _effects.emit(NotebookEffect.Refresh(full = false))
            currentPage()?.takeIf { it.id == saved.pageId }?.let { session?.askAgain(saved, it.id) }
        }
    }

    /** An answer may have spilled onto the pages after the one it started on: erase it there too. */
    private suspend fun eraseAnswerEverywhere(ex: ExchangeId) {
        val pages = notebook?.pages ?: return
        val here = pageIndex
        for (i in here downTo 0) if (!eraseAnswerOn(pages[i].id, ex) && i < here) break
        for (i in here + 1 until pages.size) if (!eraseAnswerOn(pages[i].id, ex)) break
    }

    private suspend fun eraseAnswerOn(pageId: PageId, ex: ExchangeId): Boolean {
        var changed = false
        editAnyPage(pageId) { s -> deps.ink.eraseAnswer(s, ex).also { changed = it !== s } }
        return changed
    }
    fun retry() { session?.onTapRetry() }
    fun continueScribe() { _state.update { it.copy(resumeMark = null) }; session?.onTapContinue() }

    /**
     * The hand finished everything planned so far (up to block [ref]) on [pageId]. Commits what it wrote as ONE undoable
     * command and returns the new stroke ids synchronously so the view does not draw them a second time.
     */
    fun onScribeDrained(written: List<TimedStroke>, ref: BlockRef?, pageId: PageId?): List<StrokeId> {
        val strokes = answerStrokes(written, ref)
        ownScope.launch {
            commitAnswer(pageId, strokes)
            if (ref != null && ref.session == sessionGen) session?.onScribeFinished(ref.reqId, ref.seq)
        }
        return strokes.map { it.id }
    }

    /** The hand was stopped by the pen: keep what it wrote and offer to continue where it stopped. */
    fun onScribeStopped(written: List<TimedStroke>, ref: BlockRef?, pageId: PageId?): List<StrokeId> {
        val strokes = answerStrokes(written, ref)
        val last = written.lastOrNull()?.points?.let { PagePoint(it.xs.last(), it.ys.last()) }
        ownScope.launch {
            commitAnswer(pageId, strokes)
            if (pageId == currentPage()?.id) _state.update { it.copy(resumeMark = last) }
        }
        return strokes.map { it.id }
    }

    /** The hand was dropped (the page turned under it): keep what it wrote, nothing to continue. */
    fun onScribeAbandoned(written: List<TimedStroke>, ref: BlockRef?, pageId: PageId?) {
        val strokes = answerStrokes(written, ref)
        ownScope.launch { commitAnswer(pageId, strokes) }
    }

    /**
     * The notebook screen went away (Settings, back) while the view model lives on. What the hand wrote is kept; a
     * question in flight or an answer being written is interrupted as if the pen touched the page, since nobody is
     * there to draw the rest. [onScreenShown] picks the page up again.
     */
    fun onScreenLeft(written: List<TimedStroke>, ref: BlockRef?, pageId: PageId?) {
        onScribeAbandoned(written, ref, pageId)
        _state.update { it.copy(resumeMark = null) }
        session?.onPenDown()
    }

    /** Back on the page: an unanswered utterance gets its pause again. */
    fun onScreenShown() { session?.onPenAborted() }

    private fun answerStrokes(written: List<TimedStroke>, ref: BlockRef?): List<Stroke> {
        val exchange = ref?.exchangeId ?: ExchangeId(deps.idGen.next())
        return written.map { t ->
            val pen = answerPen(t.tone, settings.answerInk, settings.codeHighlight)
            val p = t.points
            val t0 = p.ts.first()
            val points = InkPoints(p.xs, p.ys, p.ps, IntArray(p.size) { p.ts[it] - t0 })
            Stroke(StrokeId(deps.idGen.next()), points, STROKE_WIDTH * pen.widthScale, pen.color, StrokeOrigin.Oracle(exchange))
        }
    }

    /**
     * The answer is longer than the page: keep what was written, add a blank page right after this one, turn to it and
     * let the hand carry on. Returns the ids of the committed strokes synchronously (the view must not redraw them).
     */
    fun onScribePageBreak(written: List<TimedStroke>, toPage: Int, ref: BlockRef?, pageId: PageId?): List<StrokeId> {
        val strokes = answerStrokes(written, ref)
        ownScope.launch {
            commitAnswer(pageId, strokes)
            val nb = notebook
            val from = nb?.pages?.indexOfFirst { it.id == pageId } ?: -1
            if (nb == null || from < 0 || from != pageIndex) return@launch // the user turned away; the hand was reset
            if (!insertPageAfter(nb, pageId)) {
                session?.onPenDown() // could not add the page: end the answer here, as interrupted
                return@launch
            }
            openPage(from + 1, full = true, keepAnswer = true)
            delay(SPILL_SETTLE_MILLIS) // let the view lay out the new page before the hand touches it
            currentPage()?.let { _scribe.emit(ScribeCommand.PageReady(toPage, it.id)) }
        }
        return strokes.map { it.id }
    }

    private suspend fun commitAnswer(pageId: PageId?, strokes: List<Stroke>) {
        if (pageId == null || strokes.isEmpty()) return
        editAnyPage(pageId) { deps.ink.commitMany(it, strokes) }
    }

    /**
     * Applies [change] to [pageId]'s ink, serialised with every other ink change. When that page is no longer open
     * (the user turned while the change was in flight) it is applied to the page's file instead, so nothing is lost or
     * lands on the wrong page.
     */
    private suspend fun editAnyPage(pageId: PageId, change: suspend (InkEditState) -> InkEditState) {
        editLock.withLock {
            val open = edit
            if (open != null && open.ink.pageId == pageId) { edit = change(open); publish() }
            else nearby[pageId] = change(nearby[pageId] ?: deps.ink.load(pageId))
        }
    }

    /** Applies [change] only if [pageId] is still the open page (undo, redo); null = nothing to do. True if it changed. */
    private suspend fun editOpenPage(pageId: PageId, change: suspend (InkEditState) -> InkEditState?): Boolean =
        editLock.withLock {
            val open = edit?.takeIf { it.ink.pageId == pageId } ?: return@withLock false
            val next = change(open)?.takeIf { it !== open } ?: return@withLock false
            edit = next; publish(); true
        }

    private fun failure(error: DomainError?) = _state.update { it.copy(failure = error?.let(::failureMessage)) }

    private fun memoryNote(note: String) {
        _state.update { it.copy(memoryNote = note) }
        memoryNoteJob?.cancel()
        memoryNoteJob = ownScope.launch { delay(MEMORY_NOTE_MILLIS); _state.update { it.copy(memoryNote = null) } }
    }

    fun dismissMemoryNote() { memoryNoteJob?.cancel(); _state.update { it.copy(memoryNote = null) } }

    private fun startSession(page: Page) {
        session?.close(); session = null; answerScribe?.close(); answerScribe = null
        val factory = deps.sessions ?: return
        val scribe = AnswerScribeSession(
            ++sessionGen, page.id, deps.hands, { settings }, { page.size }, { edit?.ink?.strokes.orEmpty() }, { printedOccupancy },
            { _scribe.tryEmit(it) }, ::failure, ::memoryNote,
        )
        answerScribe = scribe
        val context = object : ConversationContextPorts {
            override suspend fun documentPages(): List<String> = deps.sources.documentPages()
            override val currentDocumentPage: Int? get() = documentPageIndex
            override val teachGoal: String? get() = notebook?.notebook?.teachGoal
            override val persona get() = PersonaRegistry.of(notebook?.notebook?.personaId)
        }
        val created = factory.create(scribe, ownScope, settings, notebook?.notebook?.listening ?: true, context)
        session = created
        statusJob?.cancel()
        statusJob = ownScope.launch {
            created.status.collect { st ->
                val waiting = st.state is ConvState.Pausing || st.state is ConvState.Held
                _state.update { if (it.askable == waiting) it else it.copy(askable = waiting) }
            }
        }
        // a lesson notebook: Nisaba opens a fresh lesson, or recaps when they come back after a while (the session decides)
        if (notebook?.notebook?.teachGoal != null) created.beginLesson(page.id, edit?.ink?.strokes?.maxOfOrNull { it.bounds.bottom })
    }

    // ---- ink ----------------------------------------------------------------------------------------------------

    private fun currentPage(): Page? = notebook?.pages?.getOrNull(pageIndex)

    /** [keepAnswer] = the page turns because a long answer spills over: the session and the hand carry on. */
    private suspend fun openPage(index: Int, full: Boolean, keepAnswer: Boolean = false) {
        val nb = notebook ?: return
        val page = nb.pages.getOrNull(index) ?: return
        if (!keepAnswer) _scribe.emit(ScribeCommand.Reset)
        editLock.withLock {
            edit?.let { nearby[it.ink.pageId] = it } // turning back finds it as it was, undo history included
            pageIndex = index
            edit = nearby.remove(page.id) ?: deps.ink.load(page.id)
        }
        preloadAround(nb, index)
        val start = PagePoint(PageSize.ENDLESS_ORIGIN, PageSize.ENDLESS_ORIGIN)
        viewOrigin = if (page.size.isEndless) deps.memory.loadView(page.id) ?: start else null
        affine = viewOrigin?.let { Affine(1f, -it.x, -it.y) } ?: affine
        _state.update { it.copy(failure = null, resumeMark = if (keepAnswer) it.resumeMark else null) }
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
        _effects.emit(NotebookEffect.Refresh(full))
    }

    /** Loads the pages either side of [index] in the background and forgets the ones further away. */
    private fun preloadAround(nb: NotebookWithPages, index: Int) {
        val keep = (index - 1..index + 1).mapNotNull { nb.pages.getOrNull(it)?.id }.toSet()
        ownScope.launch {
            editLock.withLock { nearby.keys.retainAll(keep) }
            for (id in keep) {
                if (editLock.withLock { id in nearby || edit?.ink?.pageId == id }) continue
                val loaded = deps.ink.load(id)
                editLock.withLock { if (id !in nearby && edit?.ink?.pageId != id && pageIndex == index) nearby[id] = loaded }
            }
        }
    }

    /** A shape-tool stroke: a drag becomes the picked shape; in Auto a rough shape is cleaned up, anything else kept. */
    private suspend fun commitShape(pageId: PageId, points: InkPoints) {
        if (points.size == 0) return
        val kind = _state.value.shape
        val clean = if (kind == ShapeKind.Auto) {
            recognizeShape(points)
        } else {
            shapeStroke(kind, PagePoint(points.xs.first(), points.ys.first()), PagePoint(points.xs.last(), points.ys.last()))
        }
        commitStroke(pageId, clean ?: points)
        if (clean != null) _effects.emit(NotebookEffect.Repaint) // the freehand live ink gives way to the clean shape
    }

    private suspend fun commitStroke(pageId: PageId, points: InkPoints) {
        val s = _state.value
        val stroke = Stroke(StrokeId(deps.idGen.next()), points, s.penSize.width, InkColor(s.penInk), StrokeOrigin.User)
        editAnyPage(pageId) { deps.ink.commit(it, stroke) }
        if (currentPage()?.id == pageId) session?.onPenUp(stroke.id, stroke.bounds, pageId)
        hideToolbarLater()
    }

    private suspend fun erase(pageId: PageId, mode: EraseMode, path: List<PagePoint>) {
        if (editOpenPage(pageId) { deps.ink.erase(it, mode, path, eraserRadius) }) _effects.emit(NotebookEffect.Refresh(full = false))
    }

    private fun toPagePoints(v: InkPoints): InkPoints {
        val xs = FloatArray(v.size); val ys = FloatArray(v.size)
        for (i in 0 until v.size) { val p = affine.toPage(v.xs[i], v.ys[i]); xs[i] = p.x; ys[i] = p.y }
        return InkPoints(xs, ys, v.ps, v.ts)
    }

    /** Hides the bar [millis] from now: soon after writing, a little later after the bar itself was used. */
    private fun hideToolbarLater(millis: Long = toolbarHideMillis) {
        hideJob?.cancel()
        hideJob = ownScope.launch {
            delay(millis)
            val s = _state.value
            if (s.toolbarVisible && !s.pickerOpen) { // never pull the bar from under an open menu
                _state.update { it.copy(toolbarVisible = false) }
                hiddenAt = nowMillis()
                _effects.emit(NotebookEffect.Refresh(full = false))
            }
        }
    }

    private var hiddenAt = Long.MIN_VALUE / 2

    /** The bar was used: keep it up a little longer. */
    private fun usedToolbar() { if (_state.value.toolbarVisible) hideToolbarLater(toolbarUseMillis) }

    private fun publish() {
        val nb = notebook ?: return
        val page = nb.pages.getOrNull(pageIndex) ?: return
        val e = edit ?: InkEditState(PageInk(page.id, emptyList()))
        _state.update {
            it.copy(
                title = nb.notebook.name, pageIndex = pageIndex, pageCount = nb.pages.size, pageSize = page.size,
                background = page.background, strokes = e.ink.strokes, canUndo = e.canUndo, canRedo = e.canRedo,
                listening = nb.notebook.listening, loading = false, endless = page.size.isEndless, viewOrigin = viewOrigin,
                lesson = nb.notebook.teachGoal != null, personaId = nb.notebook.personaId,
            )
        }
        if (_state.value.hintPending && e.ink.strokes.isNotEmpty()) {
            _state.update { it.copy(hintPending = false) }
            ownScope.launch { deps.memory.markHintSeen() }
        }
    }

    /**
     * Debug harness: the app's own hand writes [text] as if the user had handwritten it, one pen event per stroke.
     * Lets the whole ask loop be exercised end to end without a stylus.
     */
    fun debugWriteAsUser(text: String) {
        ownScope.launch {
            val page = currentPage()?.size ?: return@launch
            val originX = viewOrigin?.x ?: 0f; val originY = viewOrigin?.y ?: 0f
            val planner = ScribePlanUseCase(
                deps.hands(HandStyle.Casual), LayoutRegion.below(page, DEBUG_LEFT + originX, DEBUG_TOP + originY, DEBUG_X_HEIGHT),
                OccupancyLookup { null },
                DEBUG_SEED, HumanParams.CASUAL, speedProfile(HandSpeed.Swift),
            )
            val step = planner(planner.start(), Block.Paragraph(listOf(Inline.Text(text))))
            for (s in step.strokes.filter { it.page == 0 }) {
                val p = s.points
                val t0 = p.ts.first()
                onPenEvent(PenEvent.PenDown(0f, 0f))
                val xs = FloatArray(p.size) { p.xs[it] * affine.scale + affine.dx }
                val ys = FloatArray(p.size) { p.ys[it] * affine.scale + affine.dy }
                onPenEvent(PenEvent.StrokeDone(InkPoints(xs, ys, p.ps, IntArray(p.size) { p.ts[it] - t0 })))
                delay(DEBUG_STROKE_GAP)
            }
        }
    }

    override fun onCleared() { session?.close() }

    companion object {
        const val STROKE_WIDTH = 3.5f
        const val ERASER_RADIUS = 16f
        private const val ANSWER_HIT = 40f
        private const val PAGE_GRID_SIZE = 36
        const val TOOLBAR_HIDE_MILLIS = 3000L
        const val TOOLBAR_USE_MILLIS = 5000L
        private const val RESHOW_GUARD_MILLIS = 500L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val PAGE_WAIT_MILLIS = 3000L
        private const val MEMORY_NOTE_MILLIS = 6000L
        private const val SPILL_SETTLE_MILLIS = 250L
        private const val DEBUG_LEFT = 200f
        private const val DEBUG_TOP = 320f
        private const val DEBUG_X_HEIGHT = 48f
        private const val DEBUG_SEED = 99L
        private const val DEBUG_STROKE_GAP = 25L
    }
}
