package com.nisaba.domain.ink

import com.nisaba.domain.notebook.FakeIdGen
import com.nisaba.domain.notebook.FakeInkStore
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PagePoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InkUseCasesTest {
    private val page = PageId("p1")
    private val store = FakeInkStore()
    private val empty = InkEditState(PageInk(page, emptyList()))
    private val a = lineStroke("a", 100f, 300f, 500f)
    private val b = lineStroke("b", 100f, 300f, 700f)

    @Test fun commitAppendsSavesAndEnablesUndo() = runTest {
        val s = CommitStrokeUseCase(store)(empty, a)
        assertEquals(listOf(a), s.ink.strokes)
        assertEquals(listOf(a), store.saved[page])
        assertTrue(s.canUndo); assertFalse(s.canRedo)
    }

    @Test fun manyStrokesAreOneUndoStep() = runTest {
        val s = CommitStrokesUseCase(store)(empty, listOf(a, b))
        assertEquals(listOf(a, b), s.ink.strokes)
        assertEquals(1, s.history.undo.size)
        assertTrue(UndoUseCase(store)(s).ink.strokes.isEmpty())
        assertSame(empty, CommitStrokesUseCase(store)(empty, emptyList()))
    }

    @Test fun undoRedoRoundTripAndPersist() = runTest {
        var s = CommitStrokeUseCase(store)(empty, a)
        s = UndoUseCase(store)(s)
        assertTrue(s.ink.strokes.isEmpty()); assertTrue(store.saved[page]!!.isEmpty()); assertTrue(s.canRedo)
        s = RedoUseCase(store)(s)
        assertEquals(listOf(a), s.ink.strokes); assertEquals(listOf(a), store.saved[page])
    }

    @Test fun erasingAnAnswerRemovesAllItsStrokesAsOneStepAndNothingElse() = runTest {
        val ex = com.nisaba.domain.shared.ExchangeId("x1")
        val ans1 = lineStroke("o1", 100f, 300f, 900f).copy(origin = StrokeOrigin.Oracle(ex))
        val ans2 = lineStroke("o2", 100f, 300f, 950f).copy(origin = StrokeOrigin.Oracle(ex))
        val other = lineStroke("o3", 100f, 300f, 1100f).copy(origin = StrokeOrigin.Oracle(com.nisaba.domain.shared.ExchangeId("x2")))
        val s = CommitStrokesUseCase(store)(empty, listOf(a, ans1, ans2, other))
        val erased = EraseAnswerUseCase(store)(s, ex)
        assertEquals(listOf(a, other), erased.ink.strokes)
        assertEquals(setOf(a, ans1, ans2, other), UndoUseCase(store)(erased).ink.strokes.toSet())
        assertSame(erased, EraseAnswerUseCase(store)(erased, ex))
        assertEquals(ex, answerAt(s.ink.strokes, PagePoint(200f, 955f), 40f))
        assertEquals(null, answerAt(s.ink.strokes, PagePoint(200f, 500f), 40f)) // the user's own stroke is not an answer
    }

    @Test fun undoOnEmptyHistoryIsNoOp() = runTest {
        assertSame(empty, UndoUseCase(store)(empty))
    }

    @Test fun strokeEraserRemovesOnlyHitStrokes() = runTest {
        val commit = CommitStrokeUseCase(store)
        val s = commit(commit(empty, a), b)
        val out = EraseUseCase(store, FakeIdGen())(s, EraseMode.Stroke, listOf(PagePoint(200f, 480f), PagePoint(200f, 520f)), 6f)
        assertEquals(listOf(b), out.ink.strokes)
        assertEquals(setOf(a, b), UndoUseCase(store)(out).ink.strokes.toSet())
    }

    @Test fun areaEraserSplitsAndUndoRestoresOriginal() = runTest {
        val s = CommitStrokeUseCase(store)(empty, a)
        val out = EraseUseCase(store, FakeIdGen("n"))(s, EraseMode.Area, listOf(PagePoint(200f, 480f), PagePoint(200f, 520f)), 12f)
        assertEquals(2, out.ink.strokes.size)
        assertTrue(out.ink.strokes.none { it.id == a.id })
        val back = UndoUseCase(store)(out)
        assertEquals(listOf(a), back.ink.strokes)
    }

    @Test fun eraserThatTouchesNothingLeavesHistoryUntouched() = runTest {
        val s = CommitStrokeUseCase(store)(empty, a)
        val out = EraseUseCase(store, FakeIdGen())(s, EraseMode.Stroke, listOf(PagePoint(900f, 900f)), 5f)
        assertSame(s, out)
    }
}
