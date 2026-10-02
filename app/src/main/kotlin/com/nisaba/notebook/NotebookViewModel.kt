package com.nisaba.notebook

import com.nisaba.domain.shared.PageSize
import com.nisaba.domain.shared.PageId
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nisaba.device.PenEvent
import com.nisaba.device.PenTool
import com.nisaba.domain.conversation.ConversationContextPorts
import com.nisaba.domain.conversation.ConversationSession
import com.nisaba.domain.conversation.ConversationSurface
import com.nisaba.domain.conversation.failureMessage
import com.nisaba.domain.ink.CommitStrokeUseCase
import com.nisaba.domain.ink.CommitStrokesUseCase
import com.nisaba.domain.ink.EraseMode
import com.nisaba.domain.ink.EraseUseCase
import com.nisaba.domain.ink.InkColor
import com.nisaba.domain.ink.InkEditState
import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.ink.LoadPageInkUseCase
import com.nisaba.domain.ink.PageInk
import com.nisaba.domain.ink.RedoUseCase
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.ink.StrokeOrigin
import com.nisaba.domain.ink.UndoUseCase
import com.nisaba.domain.notebook.InsertPageUseCase
import com.nisaba.domain.notebook.NotebookWithPages
import com.nisaba.domain.notebook.ObserveNotebookUseCase
import com.nisaba.domain.notebook.Page
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.notebook.RenameNotebookUseCase
import com.nisaba.domain.notebook.SetListeningUseCase
import com.nisaba.domain.settings.AnswerInk
import com.nisaba.domain.settings.HandStyle
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.Affine
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.IdGen
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.Outcome
import com.nisaba.domain.shared.PagePoint
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
    /** Text of the notebook's document, one string per page (empty for plain notebooks). */
    val documentPages: suspend () -> List<String> = { emptyList() },
    /** Occupancy of what is printed on a document page, so answers avoid it (null for blank pages). */
    val backgroundOccupancy: suspend (Page) -> Occupancy? = { null },
)

/** What the app remembers for the user between visits: the first-run hint, and where each endless canvas was left. */
class NotebookMemory(
    val markHintSeen: suspend () -> Unit = {},
    val loadView: (PageId) -> PagePoint? = { null },
    val saveView: (PageId, PagePoint) -> Unit = { _, _ -> },
)

/** [scopeOverride] lets tests run on a test scope; production uses [viewModelScope]. */
class NotebookViewModel(
    private val deps: NotebookDeps,
    scopeOverride: CoroutineScope? = null,
    private val eraserRadius: Float = ERASER_RADIUS,
    private val toolbarHideMillis: Long = TOOLBAR_HIDE_MILLIS,
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
    private var affine = Affine(1f, 0f, 0f)
    private var hideJob: Job? = null
    private var opened = false
    private var settings = Settings()
    private var session: ConversationSession? = null
    private var answerScribe: AnswerScribeSession? = null
    private var documentPageIndex: Int? = null
    private var printedOccupancy: Occupancy? = null
    private val exchangeIds = HashMap<Int, ExchangeId>()

    init {
        ownScope.launch { deps.settings.collect { s -> settings = s; onSettings(s) } }
        ownScope.launch {
            deps.observe(deps.notebookId).collect { nb ->
                notebook = nb
                if (nb == null) return@collect
                if (!opened) { opened = true; openPage(0, full = false) } else publish()
                session?.updateSettings(settings, nb.notebook.listening)
            }
        }
    }

    private fun onSettings(s: Settings) {
        session?.updateSettings(s, notebook?.notebook?.listening ?: true)
        _state.update {
            it.copy(
                answerColor = if (s.answerInk == AnswerInk.Black) InkColor.BLACK.argb else InkColor.LAPIS.argb,
                hintPending = !s.hintSeen,
            )
        }
    }

    /** Page-space top-left of the view on an endless canvas (null on paged notebooks). */
    private var viewOrigin: PagePoint? = null

    fun onViewSize(w: Int, h: Int) {
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
            is PenEvent.StrokeDone -> ownScope.launch { commitStroke(e.points) }
            is PenEvent.EraseDone -> ownScope.launch { erase(e.path); session?.onPenAborted() }
        }
    }

    fun setTool(tool: PenTool) = _state.update { it.copy(tool = tool) }

    fun undo() = ownScope.launch {
        val s = edit ?: return@launch
        if (!s.canUndo) return@launch
        edit = deps.ink.undo(s); publish(); _effects.emit(NotebookEffect.Refresh(full = false))
    }

    fun redo() = ownScope.launch {
        val s = edit ?: return@launch
        if (!s.canRedo) return@launch
        edit = deps.ink.redo(s); publish(); _effects.emit(NotebookEffect.Refresh(full = false))
    }

    /** +1 next page (appends a page at the end), −1 previous. */
    fun turnPage(delta: Int) {
        ownScope.launch {
            val nb = notebook ?: return@launch
            val target = pageIndex + delta
            if (target < 0) return@launch
            if (target >= nb.pages.size) {
                val after = nb.pages.lastOrNull()?.id
                if (deps.insertPage(deps.notebookId, after) !is Outcome.Ok) return@launch
                notebook = withTimeoutOrNull(PAGE_WAIT_MILLIS) {
                    deps.observe(deps.notebookId).first { it != null && it.pages.size > nb.pages.size }
                } ?: return@launch
            }
            openPage(target, full = true)
        }
    }

    /** Inserts a blank page right after the current one and opens it (also how PDF notebooks get room to write). */
    fun insertBlankPage() {
        ownScope.launch {
            val nb = notebook ?: return@launch
            val current = nb.pages.getOrNull(pageIndex) ?: return@launch
            if (deps.insertPage(deps.notebookId, current.id) !is Outcome.Ok) return@launch
            notebook = withTimeoutOrNull(PAGE_WAIT_MILLIS) {
                deps.observe(deps.notebookId).first { it != null && it.pages.size > nb.pages.size }
            } ?: return@launch
            openPage(pageIndex + 1, full = true)
        }
    }

    fun showToolbar() { _state.update { it.copy(toolbarVisible = true) }; hideToolbarLater() }

    fun beginRename() = _state.update { it.copy(renaming = true) }
    fun cancelRename() = _state.update { it.copy(renaming = false) }
    fun confirmRename(name: String) {
        ownScope.launch { deps.rename(deps.notebookId, name); _state.update { it.copy(renaming = false) } }
    }

    // ---- the ask loop -------------------------------------------------------------------------------------------

    fun toggleListening() {
        val nb = notebook?.notebook ?: return
        ownScope.launch { deps.setListening(nb.id, !nb.listening) }
    }

    fun askNow() { session?.onAskNow() }
    fun retry() { session?.onTapRetry() }
    fun continueScribe() { _state.update { it.copy(resumeMark = null) }; session?.onTapContinue() }

    /**
     * The hand finished everything planned so far. Commits what it wrote as ONE undoable command and returns the new
     * stroke ids synchronously so the view does not draw them a second time.
     */
    fun onScribeDrained(written: List<TimedStroke>): List<StrokeId> {
        val strokes = answerStrokes(written)
        ownScope.launch { commitAnswer(strokes); session?.onScribeFinished() }
        return strokes.map { it.id }
    }

    /** The hand was stopped by the pen: keep what it wrote and offer to continue where it stopped. */
    fun onScribeStopped(written: List<TimedStroke>): List<StrokeId> {
        val strokes = answerStrokes(written)
        val last = written.lastOrNull()?.points?.let { PagePoint(it.xs.last(), it.ys.last()) }
        ownScope.launch { commitAnswer(strokes); _state.update { it.copy(resumeMark = last) } }
        return strokes.map { it.id }
    }

    private fun answerStrokes(written: List<TimedStroke>): List<Stroke> {
        val exchange = exchangeIds.getOrPut(answerScribe?.currentReq ?: 0) { ExchangeId(deps.idGen.next()) }
        val color = if (settings.answerInk == AnswerInk.Black) InkColor.BLACK else InkColor.LAPIS
        return written.map { t ->
            val p = t.points
            val t0 = p.ts.first()
            val points = InkPoints(p.xs, p.ys, p.ps, IntArray(p.size) { p.ts[it] - t0 })
            Stroke(StrokeId(deps.idGen.next()), points, STROKE_WIDTH, color, StrokeOrigin.Oracle(exchange))
        }
    }

    /**
     * The answer is longer than the page: keep what was written, add a blank page right after this one, turn to it and
     * let the hand carry on. Returns the ids of the committed strokes synchronously (the view must not redraw them).
     */
    fun onScribePageBreak(written: List<TimedStroke>, toPage: Int): List<StrokeId> {
        val strokes = answerStrokes(written)
        ownScope.launch {
            commitAnswer(strokes)
            val nb = notebook ?: return@launch
            val current = nb.pages.getOrNull(pageIndex) ?: return@launch
            if (deps.insertPage(deps.notebookId, current.id) !is Outcome.Ok) return@launch
            notebook = withTimeoutOrNull(PAGE_WAIT_MILLIS) {
                deps.observe(deps.notebookId).first { it != null && it.pages.size > nb.pages.size }
            } ?: return@launch
            openPage(pageIndex + 1, full = true, keepAnswer = true)
            delay(SPILL_SETTLE_MILLIS) // let the view lay out the new page before the hand touches it
            _scribe.emit(ScribeCommand.PageReady(toPage))
        }
        return strokes.map { it.id }
    }

    private suspend fun commitAnswer(strokes: List<Stroke>) {
        val state = edit ?: return
        if (strokes.isEmpty()) return
        edit = deps.ink.commitMany(state, strokes)
        publish()
    }

    private fun failure(error: DomainError?) = _state.update { it.copy(failure = error?.let(::failureMessage)) }

    private fun startSession(page: Page) {
        session?.close(); session = null; answerScribe = null
        val factory = deps.sessions ?: return
        val scribe = AnswerScribeSession(
            deps.hands, { settings }, { page.size }, { edit?.ink?.strokes.orEmpty() }, { printedOccupancy },
            { _scribe.tryEmit(it) }, ::failure,
        )
        answerScribe = scribe
        val context = object : ConversationContextPorts {
            override suspend fun documentPages(): List<String> = deps.documentPages()
            override val currentDocumentPage: Int? get() = documentPageIndex
        }
        session = factory.create(scribe, ownScope, settings, notebook?.notebook?.listening ?: true, context)
    }

    // ---- ink ----------------------------------------------------------------------------------------------------

    private fun currentPage(): Page? = notebook?.pages?.getOrNull(pageIndex)

    /** [keepAnswer] = the page turns because a long answer spills over: the session and the hand carry on. */
    private suspend fun openPage(index: Int, full: Boolean, keepAnswer: Boolean = false) {
        val nb = notebook ?: return
        val page = nb.pages.getOrNull(index) ?: return
        if (!keepAnswer) _scribe.emit(ScribeCommand.Reset)
        pageIndex = index
        val start = PagePoint(PageSize.ENDLESS_ORIGIN, PageSize.ENDLESS_ORIGIN)
        viewOrigin = if (page.size.isEndless) deps.memory.loadView(page.id) ?: start else null
        affine = viewOrigin?.let { Affine(1f, -it.x, -it.y) } ?: affine
        edit = deps.ink.load(page.id)
        _state.update { it.copy(failure = null, resumeMark = if (keepAnswer) it.resumeMark else null) }
        if (!keepAnswer) {
            documentPageIndex = (page.background as? PageBackground.DocumentPage)?.index
            printedOccupancy = null
            if (page.background is PageBackground.DocumentPage) ownScope.launch { printedOccupancy = deps.backgroundOccupancy(page) }
            startSession(page)
        } else {
            printedOccupancy = null
        }
        publish()
        _effects.emit(NotebookEffect.Refresh(full))
    }

    private suspend fun commitStroke(view: InkPoints) {
        val state = edit ?: return
        val page = toPagePoints(view)
        val stroke = Stroke(StrokeId(deps.idGen.next()), page, STROKE_WIDTH, InkColor.BLACK, StrokeOrigin.User)
        edit = deps.ink.commit(state, stroke)
        publish()
        session?.onPenUp(stroke.id, stroke.bounds, state.ink.pageId)
        hideToolbarLater()
    }

    private suspend fun erase(path: List<PagePoint>) {
        val state = edit ?: return
        val tool = _state.value.tool
        val mode = if (tool == PenTool.AreaEraser) EraseMode.Area else EraseMode.Stroke
        val pagePath = path.map { affine.toPage(it.x, it.y) }
        val next = deps.ink.erase(state, mode, pagePath, eraserRadius)
        if (next !== state) { edit = next; publish(); _effects.emit(NotebookEffect.Refresh(full = false)) }
    }

    private fun toPagePoints(v: InkPoints): InkPoints {
        val xs = FloatArray(v.size); val ys = FloatArray(v.size)
        for (i in 0 until v.size) { val p = affine.toPage(v.xs[i], v.ys[i]); xs[i] = p.x; ys[i] = p.y }
        return InkPoints(xs, ys, v.ps, v.ts)
    }

    private fun hideToolbarLater() {
        hideJob?.cancel()
        hideJob = ownScope.launch {
            delay(toolbarHideMillis)
            if (_state.value.toolbarVisible) {
                _state.update { it.copy(toolbarVisible = false) }
                _effects.emit(NotebookEffect.Refresh(full = false))
            }
        }
    }

    private fun publish() {
        val nb = notebook ?: return
        val page = nb.pages.getOrNull(pageIndex) ?: return
        val e = edit ?: InkEditState(PageInk(page.id, emptyList()))
        _state.update {
            it.copy(
                title = nb.notebook.name, pageIndex = pageIndex, pageCount = nb.pages.size, pageSize = page.size,
                background = page.background, strokes = e.ink.strokes, canUndo = e.canUndo, canRedo = e.canRedo,
                listening = nb.notebook.listening, loading = false, endless = page.size.isEndless, viewOrigin = viewOrigin,
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
        const val TOOLBAR_HIDE_MILLIS = 3000L
        private const val PAGE_WAIT_MILLIS = 3000L
        private const val SPILL_SETTLE_MILLIS = 250L
        private const val DEBUG_LEFT = 200f
        private const val DEBUG_TOP = 320f
        private const val DEBUG_X_HEIGHT = 48f
        private const val DEBUG_SEED = 99L
        private const val DEBUG_STROKE_GAP = 25L
    }
}
