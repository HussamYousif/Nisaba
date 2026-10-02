package com.nisaba.notebook

import com.nisaba.device.PenEvent
import com.nisaba.device.PenTool
import com.nisaba.domain.ink.CommitStrokeUseCase
import com.nisaba.domain.ink.CommitStrokesUseCase
import com.nisaba.domain.ink.EraseUseCase
import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.ink.StrokeOrigin
import com.nisaba.scribe.motion.TimedStroke
import com.nisaba.domain.ink.LoadPageInkUseCase
import com.nisaba.domain.ink.RedoUseCase
import com.nisaba.domain.ink.UndoUseCase
import com.nisaba.domain.notebook.CreateNotebookUseCase
import com.nisaba.domain.notebook.FakeClock
import com.nisaba.domain.notebook.FakeIdGen
import com.nisaba.domain.notebook.FakeInkStore
import com.nisaba.domain.notebook.FakeNotebookStore
import com.nisaba.domain.notebook.InsertPageUseCase
import com.nisaba.domain.notebook.ObserveNotebookUseCase
import com.nisaba.domain.notebook.RenameNotebookUseCase
import com.nisaba.domain.notebook.SetListeningUseCase
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PagePoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotebookViewModelTest {
    private val store = FakeNotebookStore()
    private val ink = FakeInkStore()
    private val ids = FakeIdGen("s")
    private val clock = FakeClock()

    private suspend fun open(scope: TestScope): Pair<NotebookViewModel, NotebookId> {
        val nb = CreateNotebookUseCase(store, clock, FakeIdGen("n"))("Maths")
        val deps = NotebookDeps(
            nb.id, ObserveNotebookUseCase(store), InsertPageUseCase(store, FakeIdGen("pg")), RenameNotebookUseCase(store, clock),
            InkUseCases(
                LoadPageInkUseCase(ink), CommitStrokeUseCase(ink), CommitStrokesUseCase(ink), EraseUseCase(ink, ids),
                UndoUseCase(ink), RedoUseCase(ink),
            ),
            SetListeningUseCase(store), ids,
        )
        val vm = NotebookViewModel(deps, scope.backgroundScope, eraserRadius = 6f, toolbarHideMillis = 3000)
        scope.runCurrent() // the page opens
        vm.onViewSize(930, 1240) // scale 0.5 → page coords are view coords × 2
        return vm to nb.id
    }

    private fun line(x0: Float, x1: Float, y: Float) = InkPoints(
        FloatArray(11) { x0 + (x1 - x0) * it / 10f }, FloatArray(11) { y }, FloatArray(11) { .5f }, IntArray(11) { it * 8 },
    )

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
        vm.showToolbar(); runCurrent()
        assertTrue(vm.state.value.toolbarVisible)
    }

    @Test fun aSpillingAnswerIsCommittedThenTurnsToANewPageAndTheHandContinues() = runTest {
        val (vm, _) = open(this)
        val commands = mutableListOf<ScribeCommand>()
        backgroundScope.launch { vm.scribeCommands.collect { commands += it } }
        runCurrent()
        val written = listOf(
            TimedStroke(
                0, InkPoints(floatArrayOf(300f, 400f), floatArrayOf(500f, 500f), floatArrayOf(.6f, .6f), intArrayOf(1000, 1100)),
                bold = false, wordIndex = 0,
            ),
        )
        val ids = vm.onScribePageBreak(written, 1)
        assertEquals(1, ids.size)
        advanceTimeBy(1_000); runCurrent()
        val s = vm.state.value
        assertEquals(2, s.pageCount); assertEquals(1, s.pageIndex)
        assertTrue(s.strokes.isEmpty(), "the spill page starts blank")
        assertEquals(1, ink.saved.values.count { it.size == 1 && it[0].id == ids[0] }, "the first page kept what was written")
        assertEquals(ScribeCommand.PageReady(1), commands.last())
        assertFalse(commands.contains(ScribeCommand.Reset), "a spill must not reset the hand")
        vm.turnPage(-1); runCurrent()
        assertEquals(ids[0], vm.state.value.strokes.single().id)
        assertTrue(vm.state.value.strokes.single().origin is StrokeOrigin.Oracle)
    }

    @Test fun renameUpdatesTitle() = runTest {
        val (vm, _) = open(this)
        vm.beginRename(); assertTrue(vm.state.value.renaming)
        vm.confirmRename("Physics"); runCurrent()
        assertEquals("Physics", vm.state.value.title); assertFalse(vm.state.value.renaming)
    }
}
