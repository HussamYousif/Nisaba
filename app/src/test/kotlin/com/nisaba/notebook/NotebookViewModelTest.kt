package com.nisaba.notebook

import com.nisaba.device.PenEvent
import com.nisaba.device.PenTool
import com.nisaba.domain.ink.CommitStrokeUseCase
import com.nisaba.domain.ink.CommitStrokesUseCase
import com.nisaba.domain.ink.EraseAnswerUseCase
import com.nisaba.domain.ink.RemoveStrokesUseCase
import com.nisaba.domain.ink.ReplaceStrokesUseCase
import com.nisaba.domain.ink.EraseUseCase
import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.ink.InkUseCases
import com.nisaba.domain.ink.ShapeKind
import com.nisaba.domain.settings.PenSize
import com.nisaba.domain.ink.StrokeOrigin
import com.nisaba.scribe.motion.TimedStroke
import com.nisaba.domain.ink.LoadPageInkUseCase
import com.nisaba.domain.ink.RedoUseCase
import com.nisaba.domain.ink.UndoUseCase
import com.nisaba.domain.notebook.CreateNotebookUseCase
import com.nisaba.domain.notebook.FakeClock
import com.nisaba.domain.notebook.FakeIdGen
import com.nisaba.domain.notebook.FakeNotebookStore
import com.nisaba.domain.notebook.InsertPageUseCase
import com.nisaba.domain.notebook.ObserveNotebookUseCase
import com.nisaba.domain.notebook.RenameNotebookUseCase
import com.nisaba.domain.notebook.SetListeningUseCase
import com.nisaba.domain.notebook.SetPersonaUseCase
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.ink.InkStore
import com.nisaba.domain.ink.PageInk
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.shared.PageId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotebookViewModelTest {
    private val store = FakeNotebookStore()
    private val ink = GatedInkStore()
    private val ids = FakeIdGen("s")
    private val clock = FakeClock()

    private suspend fun open(
        scope: TestScope,
        sizes: List<com.nisaba.domain.shared.PageSize> = emptyList(),
    ): Pair<NotebookViewModel, NotebookId> {
        val create = CreateNotebookUseCase(store, clock, FakeIdGen("n"))
        val nb = if (sizes.isEmpty()) create("Maths") else create("Book", com.nisaba.domain.shared.DocumentId("d"), sizes)
        val deps = NotebookDeps(
            nb.id, ObserveNotebookUseCase(store), InsertPageUseCase(store, FakeIdGen("pg")), RenameNotebookUseCase(store, clock),
            InkUseCases(
                LoadPageInkUseCase(ink), CommitStrokeUseCase(ink), CommitStrokesUseCase(ink), EraseUseCase(ink, ids),
                UndoUseCase(ink), RedoUseCase(ink), EraseAnswerUseCase(ink), ReplaceStrokesUseCase(ink), RemoveStrokesUseCase(ink),
            ),
            SetListeningUseCase(store), ids,
            changes = NotebookChanges(
                SetPersonaUseCase(store), setStyle = com.nisaba.domain.notebook.SetAnswerStyleUseCase(store)::invoke,
                setPrivate = com.nisaba.domain.notebook.SetPrivateUseCase(store)::invoke,
            ),
        )
        val vm = NotebookViewModel(
            deps, scope.backgroundScope, eraserRadius = 6f, toolbarHideMillis = 3000, nowMillis = { scope.testScheduler.currentTime },
        )
        scope.runCurrent() // the page opens
        vm.onViewSize(930, 1240) // scale 0.5 → page coords are view coords × 2
        return vm to nb.id
    }

    private fun line(x0: Float, x1: Float, y: Float) = InkPoints(
        FloatArray(11) { x0 + (x1 - x0) * it / 10f }, FloatArray(11) { y }, FloatArray(11) { .5f }, IntArray(11) { it * 8 },
    )

    @Test fun aPageWhoseInkWasKeptAsideSaysSo() = runTest {
        ink.setAside = true
        val (vm, _) = open(this)
        assertEquals(NotebookViewModel.SET_ASIDE_NOTICE, vm.state.value.notice)
        advanceTimeBy(NotebookViewModel.NOTICE_MILLIS * 2); runCurrent()
        assertEquals(NotebookViewModel.SET_ASIDE_NOTICE, vm.state.value.notice) // a warning stays until tapped
        vm.dismissNotice(); assertEquals(null, vm.state.value.notice)
    }

    @Test fun thePenMapsThroughEachPagesOwnFit() = runTest {
        val tall = com.nisaba.domain.shared.PageSize(1860f, 2632f) // an A4 PDF page; the first one is a plain portrait page
        val (vm, _) = open(this, listOf(com.nisaba.domain.shared.PageSize.PORTRAIT, tall))
        vm.turnPage(1); runCurrent()
        vm.onPenEvent(PenEvent.StrokeDone(line(465f, 470f, 620f))); runCurrent()
        val p = vm.state.value.strokes.single().points
        val fit = com.nisaba.domain.shared.fitTransform(tall, 930f, 1240f)
        assertEquals((465f - fit.dx) / fit.scale, p.xs.first(), 1f) // not the first page's ×2
        assertEquals(620f / fit.scale, p.ys.first(), 1f)
    }

    @Test fun aNoticeGoesByItself() = runTest {
        val (vm, _) = open(this)
        vm.togglePrivate(); runCurrent()
        assertEquals(PRIVATE_ON, vm.state.value.notice)
        advanceTimeBy(NotebookViewModel.NOTICE_MILLIS + 1); runCurrent()
        assertEquals(null, vm.state.value.notice)
    }

    @Test fun theVoiceCanBeChangedFromTheMenu() = runTest {
        val (vm, id) = open(this)
        assertEquals(PersonaRegistry.DEFAULT_ID, vm.state.value.personaId)
        vm.openVoice(); assertTrue(vm.state.value.voiceOpen && vm.state.value.overlayOpen) // the pen pauses under it
        val habits = com.nisaba.domain.notebook.AnswerStyle(
            com.nisaba.domain.notebook.AnswerMode.QuestionsOnly, com.nisaba.domain.notebook.AnswerLength.Brief,
        )
        vm.setVoice("writer", habits); runCurrent()
        assertFalse(vm.state.value.voiceOpen)
        assertEquals("writer", store.get(id)!!.notebook.personaId)
        assertEquals("writer", vm.state.value.personaId)
        assertEquals(habits, store.get(id)!!.notebook.style)
        assertEquals(habits, vm.state.value.style)
    }

    @Test fun opensFirstPage() = runTest {
        val (vm, _) = open(this)
        val s = vm.state.value
        assertFalse(s.loading); assertEquals("Maths", s.title); assertEquals(1, s.pageCount); assertEquals(0, s.pageIndex)
        assertTrue(s.strokes.isEmpty())
    }

    @Test fun strokeIsConvertedToPageSpaceSavedAndUndoable() = runTest {
        val (vm, _) = open(this)
        vm.onPenEvent(PenEvent.StrokeDone(line(100f, 200f, 300f)))
        runCurrent()
        val stroke = vm.state.value.strokes.single()
        assertEquals(200f, stroke.points.xs.first(), 0.01f)      // 100 view px → 200 page units
        assertEquals(600f, stroke.points.ys.first(), 0.01f)
        assertEquals(1, ink.saved.values.single().size)
        assertTrue(vm.state.value.canUndo)
    }

    @Test fun undoAndRedoRestoreInk() = runTest {
        val (vm, _) = open(this)
        vm.onPenEvent(PenEvent.StrokeDone(line(100f, 200f, 300f))); runCurrent()
        vm.undo(); runCurrent()
        assertTrue(vm.state.value.strokes.isEmpty()); assertTrue(vm.state.value.canRedo); assertFalse(vm.state.value.canUndo)
        vm.redo(); runCurrent()
        assertEquals(1, vm.state.value.strokes.size)
    }

    @Test fun strokeEraserRemovesTouchedStrokeOnly() = runTest {
        val (vm, _) = open(this)
        vm.onPenEvent(PenEvent.StrokeDone(line(100f, 200f, 300f)))
        vm.onPenEvent(PenEvent.StrokeDone(line(100f, 200f, 500f))); runCurrent()
        vm.setTool(PenTool.StrokeEraser)
        vm.onPenEvent(PenEvent.EraseDone(listOf(PagePoint(150f, 280f), PagePoint(150f, 320f)))); runCurrent()
        val left = vm.state.value.strokes.single()
        assertEquals(1000f, left.points.ys.first(), 0.01f)
    }

    @Test fun areaEraserSplitsAStroke() = runTest {
        val (vm, _) = open(this)
        vm.onPenEvent(PenEvent.StrokeDone(line(100f, 300f, 300f))); runCurrent()
        vm.setTool(PenTool.AreaEraser)
        vm.onPenEvent(PenEvent.EraseDone(listOf(PagePoint(200f, 290f), PagePoint(200f, 310f)))); runCurrent()
        assertEquals(2, vm.state.value.strokes.size)
    }

    @Test fun aPenStrokeWithAnEraserPickedErasesInsteadOfInking() = runTest { // the Onyx pen tip reports strokes either way
        val (vm, _) = open(this)
        vm.onPenEvent(PenEvent.StrokeDone(line(100f, 200f, 300f))); runCurrent()
        vm.setTool(PenTool.StrokeEraser)
        vm.onPenEvent(PenEvent.StrokeDone(InkPoints(floatArrayOf(150f, 150f), floatArrayOf(280f, 320f), FloatArray(2), IntArray(2))))
        runCurrent()
        assertTrue(vm.state.value.strokes.isEmpty())
    }

    @Test fun thePenWritesInItsPickedInkAndThickness() = runTest {
        val (vm, _) = open(this)
        vm.tapPen() // the pen is already picked, so its panel opens
        assertEquals(ToolPanel.Pen, vm.state.value.toolPanel)
        vm.setPenInk(0xFFC0281C.toInt()); vm.setPenSize(PenSize.Bold); vm.closePanel()
        vm.onPenEvent(PenEvent.StrokeDone(line(100f, 200f, 300f))); runCurrent()
        val s = vm.state.value.strokes.single()
        assertEquals(0xFFC0281C.toInt(), s.color.argb); assertEquals(PenSize.Bold.width, s.width)
    }

    @Test fun aDragWithTheShapeToolBecomesTheCleanShape() = runTest {
        val (vm, _) = open(this)
        vm.tapShape(); vm.tapShape()
        assertEquals(ToolPanel.Shapes, vm.state.value.toolPanel)
        vm.setShape(ShapeKind.Rectangle)
        val effects = mutableListOf<NotebookEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        val xs = floatArrayOf(100f, 180f, 260f, 300f); val ys = floatArrayOf(100f, 140f, 120f, 200f)
        val wobbly = InkPoints(xs, ys, FloatArray(4) { .5f }, IntArray(4))
        vm.onPenEvent(PenEvent.StrokeDone(wobbly)); runCurrent()
        val b = vm.state.value.strokes.single().bounds
        assertEquals(200f, b.left, 8f); assertEquals(600f, b.right, 8f); assertEquals(200f, b.top, 8f); assertEquals(400f, b.bottom, 8f)
        assertTrue(NotebookEffect.Repaint in effects)
    }

    @Test fun autoShapesKeepWritingAsDrawn() = runTest {
        val (vm, _) = open(this)
        vm.setTool(PenTool.Shape) // Auto by default
        val xs = floatArrayOf(100f, 120f, 140f, 160f, 180f, 200f); val ys = floatArrayOf(100f, 160f, 100f, 160f, 100f, 160f)
        val zigzag = InkPoints(xs, ys, FloatArray(6) { .5f }, IntArray(6))
        vm.onPenEvent(PenEvent.StrokeDone(zigzag)); runCurrent()
        assertEquals(6, vm.state.value.strokes.single().points.size)
    }

    @Test fun turningPastTheLastPageAppendsOneAndRequestsFullRefresh() = runTest {
        val (vm, _) = open(this)
        val effects = mutableListOf<NotebookEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        runCurrent()
        vm.onPenEvent(PenEvent.StrokeDone(line(100f, 200f, 300f))); runCurrent()
        vm.turnPage(+1); runCurrent()
        val s = vm.state.value
        assertEquals(2, s.pageCount); assertEquals(1, s.pageIndex); assertTrue(s.strokes.isEmpty()); assertFalse(s.canUndo)
        assertTrue(effects.contains(NotebookEffect.Refresh(full = true)))
        vm.turnPage(-1); runCurrent()
        assertEquals(0, vm.state.value.pageIndex); assertEquals(1, vm.state.value.strokes.size)
        assertTrue(vm.state.value.canUndo, "the page comes back with its undo history")
    }

    @Test fun neighbouringPagesArePreloadedAndStayCurrent() = runTest {
        val (vm, nbId) = open(this)
        vm.turnPage(+1); runCurrent(); vm.turnPage(+1); runCurrent()     // three pages, on the last
        val pages = store.books.value[nbId]!!.pages.map { it.id }
        val writtenElsewhere = listOf(TimedStroke(0, line(100f, 200f, 300f), bold = false, wordIndex = 0))
        vm.onScribeAbandoned(writtenElsewhere, null, pages[1]); runCurrent() // an answer lands on the (preloaded) middle page
        vm.turnPage(-1); runCurrent()
        assertEquals(1, vm.state.value.strokes.size, "the preloaded copy saw the change")
        assertEquals(1, ink.saved[pages[1]]!!.size)
    }

    @Test fun turningBeforeTheFirstPageDoesNothing() = runTest {
        val (vm, _) = open(this)
        vm.turnPage(-1); runCurrent()
        assertEquals(0, vm.state.value.pageIndex); assertEquals(1, vm.state.value.pageCount)
    }

    @Test fun toolbarHidesAfterWritingAndComesBackOnRequest() = runTest {
        val (vm, _) = open(this)
        assertTrue(vm.state.value.toolbarVisible)
        vm.onPenEvent(PenEvent.StrokeDone(line(100f, 200f, 300f))); runCurrent()
        advanceTimeBy(3100); runCurrent()
        assertFalse(vm.state.value.toolbarVisible)
        advanceTimeBy(600)                                                     // past the guard against a racing tap
        vm.showToolbar(); runCurrent()
        assertTrue(vm.state.value.toolbarVisible)
    }

    @Test fun aSpillingAnswerIsCommittedThenTurnsToANewPageAndTheHandContinues() = runTest {
        val (vm, nbId) = open(this)
        val first = store.books.value[nbId]!!.pages.first().id
        val commands = mutableListOf<ScribeCommand>()
        backgroundScope.launch { vm.scribeCommands.collect { commands += it } }
        runCurrent()
        val written = listOf(
            TimedStroke(
                0, InkPoints(floatArrayOf(300f, 400f), floatArrayOf(500f, 500f), floatArrayOf(.6f, .6f), intArrayOf(1000, 1100)),
                bold = false, wordIndex = 0,
            ),
        )
        val ids = vm.onScribePageBreak(written, 1, null, first)
        assertEquals(1, ids.size)
        advanceTimeBy(1_000); runCurrent()
        val s = vm.state.value
        assertEquals(2, s.pageCount); assertEquals(1, s.pageIndex)
        assertTrue(s.strokes.isEmpty(), "the spill page starts blank")
        assertEquals(1, ink.saved.values.count { it.size == 1 && it[0].id == ids[0] }, "the first page kept what was written")
        val second = store.books.value[nbId]!!.pages.single { it.id != first }.id
        assertEquals(ScribeCommand.PageReady(1, second), commands.last())
        assertFalse(commands.contains(ScribeCommand.Reset), "a spill must not reset the hand")
        vm.turnPage(-1); runCurrent()
        assertEquals(ids[0], vm.state.value.strokes.single().id)
        assertTrue(vm.state.value.strokes.single().origin is StrokeOrigin.Oracle)
    }

    @Test fun strokesMadeWhileASaveIsSlowAreAllKept() = runTest {
        val (vm, _) = open(this)
        ink.gate = CompletableDeferred()
        vm.onPenEvent(PenEvent.StrokeDone(line(100f, 200f, 300f)))
        vm.onPenEvent(PenEvent.StrokeDone(line(100f, 200f, 500f)))
        runCurrent()
        ink.gate!!.complete(Unit); runCurrent()
        assertEquals(2, vm.state.value.strokes.size)
        assertEquals(2, ink.saved.values.single().size)
    }

    @Test fun anAnswerCommittedAfterThePageTurnedLandsOnItsOwnPage() = runTest {
        val (vm, nbId) = open(this)
        val first = store.books.value[nbId]!!.pages.first().id
        ink.gate = CompletableDeferred()
        val written = listOf(TimedStroke(0, line(100f, 200f, 300f), bold = false, wordIndex = 0))
        vm.onScribeAbandoned(written, null, first)
        vm.turnPage(+1); runCurrent()
        ink.gate!!.complete(Unit); runCurrent()
        assertEquals(1, vm.state.value.pageIndex)
        assertTrue(vm.state.value.strokes.isEmpty(), "the new page stays blank")
        assertEquals(1, ink.saved[first]!!.size)
        vm.onPenEvent(PenEvent.StrokeDone(line(100f, 200f, 300f))); runCurrent()
        assertEquals(1, ink.saved[first]!!.size, "new writing goes to the open page")
        assertEquals(1, vm.state.value.strokes.size)
    }

    @Test fun erasingAnAnswerClearsItFromEveryPageItSpilledOnto() = runTest {
        val (vm, nbId) = open(this)
        vm.turnPage(+1); runCurrent(); vm.turnPage(+1); runCurrent()                  // three pages
        val pages = store.books.value[nbId]!!.pages.map { it.id }
        val ref = com.nisaba.canvas.BlockRef(1, 1, 1, com.nisaba.domain.shared.ExchangeId("x"))
        val stroke = listOf(TimedStroke(0, line(100f, 200f, 300f), bold = false, wordIndex = 0))
        vm.onScribeAbandoned(stroke, ref, pages[0]); vm.onScribeAbandoned(stroke, ref, pages[1]); runCurrent()
        vm.onPenEvent(PenEvent.StrokeDone(line(100f, 200f, 600f))); runCurrent()     // the user's own ink on page 3 stays
        vm.turnPage(-1); runCurrent()                                                 // on page 2, part of the answer
        vm.eraseAnswer(com.nisaba.domain.shared.ExchangeId("x")); runCurrent()
        assertTrue(ink.saved[pages[0]].orEmpty().isEmpty() && ink.saved[pages[1]].orEmpty().isEmpty())
        assertEquals(1, ink.saved[pages[2]]!!.size)
        assertTrue(vm.state.value.strokes.isEmpty())
    }

    @Test fun pickingASpotInTheOverviewCentresTheViewOnIt() = runTest {
        val (vm, _) = open(this)
        val effects = mutableListOf<NotebookEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.onViewSize(1000, 800); vm.showOverview(); runCurrent()
        assertTrue(vm.state.value.overview)
        vm.jumpTo(PagePoint(5000f, 5000f)); runCurrent()
        assertFalse(vm.state.value.overview)
        assertEquals(NotebookEffect.PanTo(PagePoint(4500f, 4600f)), effects.first { it is NotebookEffect.PanTo })
    }

    @Test fun anOpenMenuKeepsTheToolbarUpUntilItCloses() = runTest {
        val (vm, _) = open(this)
        vm.showToolbar(); vm.openMenu(); advanceTimeBy(5_000); runCurrent()
        assertTrue(vm.state.value.toolbarVisible && vm.state.value.menuOpen)
        vm.closeMenu(); advanceTimeBy(5_100); runCurrent()
        assertFalse(vm.state.value.toolbarVisible)
    }

    @Test fun thePageGridOpensOnTheCurrentScreenfulAndJumpsToAPage() = runTest {
        val (vm, _) = open(this)
        repeat(3) { vm.turnPage(+1); runCurrent() }                                 // four pages, on the last
        vm.openPageGrid(); runCurrent()
        assertTrue(vm.state.value.pageGridOpen); assertEquals(0, vm.state.value.pageGridScreen)
        vm.goToPage(1); runCurrent()
        assertFalse(vm.state.value.pageGridOpen)
        assertEquals(1, vm.state.value.pageIndex)
        vm.showToolbar(); vm.openPageGrid(); advanceTimeBy(5_000); runCurrent()
        assertTrue(vm.state.value.toolbarVisible, "the bar stays while the grid is open")
    }

    @Test fun usingTheBarKeepsItLongerAndATapRightAfterItHidesDoesNotBringItBack() = runTest {
        val (vm, _) = open(this)
        vm.showToolbar(); advanceTimeBy(4_000); runCurrent()
        assertTrue(vm.state.value.toolbarVisible, "5 s after it was summoned")
        advanceTimeBy(1_100); runCurrent()
        assertFalse(vm.state.value.toolbarVisible)
        vm.showToolbar(); runCurrent()
        assertFalse(vm.state.value.toolbarVisible, "a tap racing the hide is ignored")
        advanceTimeBy(600); vm.showToolbar(); runCurrent()
        assertTrue(vm.state.value.toolbarVisible)
    }

    @Test fun renameUpdatesTitle() = runTest {
        val (vm, _) = open(this)
        vm.beginRename(); assertTrue(vm.state.value.renaming)
        vm.confirmRename("Physics"); runCurrent()
        assertEquals("Physics", vm.state.value.title); assertFalse(vm.state.value.renaming)
    }
    private fun loop(l: Float, t: Float, r: Float, b: Float) =
        InkPoints(floatArrayOf(l, r, r, l, l), floatArrayOf(t, t, b, b, t), FloatArray(5) { .5f }, IntArray(5) { it * 8 })

    @Test fun theLassoPicksMovesRecoloursAndDeletesInkEachAsOneUndoStep() = runTest {
        val (vm, _) = open(this)
        vm.onPenEvent(PenEvent.StrokeDone(line(100f, 200f, 300f))); runCurrent() // page 200..400 at y 600
        vm.onPenEvent(PenEvent.StrokeDone(line(100f, 200f, 500f))); runCurrent() // page y 1000
        vm.setTool(PenTool.Lasso)
        vm.onPenEvent(PenEvent.StrokeDone(loop(80f, 280f, 220f, 320f))); runCurrent() // a loop round the first
        val sel = assertNotNull(vm.state.value.selection)
        assertEquals(1, sel.ids.size)
        assertEquals(2, vm.state.value.strokes.size) // the loop left no ink
        vm.onPenEvent(PenEvent.StrokeDone(line(150f, 200f, 300f))); runCurrent() // a drag from inside the box
        val moved = vm.state.value.strokes.first { it.id in sel.ids }
        assertEquals(300f, moved.points.xs.first(), 0.01f)
        assertEquals(300f, vm.state.value.selection!!.bounds.left, 0.01f) // the box follows
        vm.recolourSelection(0xFFC0281C.toInt()); runCurrent()
        assertEquals(0xFFC0281C.toInt(), vm.state.value.strokes.first { it.id in sel.ids }.color.argb)
        vm.deleteSelection(); runCurrent()
        assertEquals(1, vm.state.value.strokes.size); assertNull(vm.state.value.selection)
        assertEquals(PenTool.Pen, vm.state.value.tool) // done with the lasso: the next writing inks again
        vm.undo(); runCurrent(); vm.undo(); runCurrent(); vm.undo(); runCurrent() // delete, recolour, move
        assertEquals(200f, vm.state.value.strokes.first { it.id in sel.ids }.points.xs.first(), 0.01f)
        vm.setTool(PenTool.Pen); assertNull(vm.state.value.selection)
    }

    @Test fun aLoopAroundNothingButPaperPicksTheAreaToAskAbout() = runTest {
        val (vm, _) = open(this)
        vm.setTool(PenTool.Lasso)
        vm.onPenEvent(PenEvent.StrokeDone(loop(100f, 100f, 300f, 200f))); runCurrent()
        val sel = assertNotNull(vm.state.value.selection)
        assertTrue(sel.ids.isEmpty() && sel.bounds.width > 300f) // printed text, say: only "Ask about it"
        vm.checkWork(); assertEquals(NOTHING_TO_CHECK, vm.state.value.notice)
    }

    @Test fun aNotebookTurnsPrivateAndAnOfferedReminderGoesOutOnATap() = runTest {
        val (vm, id) = open(this)
        vm.togglePrivate(); runCurrent()
        assertTrue(store.get(id)!!.notebook.isPrivate && vm.state.value.isPrivate)
        assertEquals(PRIVATE_ON, vm.state.value.notice)
        val r = com.nisaba.domain.conversation.Reminder("call Ola", "2026-10-16T09:00")
        val effects = mutableListOf<NotebookEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }; runCurrent()
        vm.mutableState.update { it.copy(reminder = r) }
        vm.addReminder(); runCurrent()
        assertEquals(listOf<NotebookEffect>(NotebookEffect.Remind(r)), effects.filterIsInstance<NotebookEffect.Remind>())
        assertNull(vm.state.value.reminder)
        assertTrue(reminderLine(r).startsWith("call Ola · "))
    }

}

/** In-memory ink whose saves can be held up (a slow disk) until [gate] completes. */
private class GatedInkStore : InkStore {
    val saved = mutableMapOf<PageId, List<Stroke>>()
    var gate: CompletableDeferred<Unit>? = null
    var setAside = false
    override suspend fun load(pageId: PageId) = PageInk(pageId, saved[pageId].orEmpty(), setAside)
    override suspend fun save(pageId: PageId, strokes: List<Stroke>) { gate?.await(); saved[pageId] = strokes }
    override suspend fun deletePages(ids: List<PageId>) { ids.forEach(saved::remove) }
}
