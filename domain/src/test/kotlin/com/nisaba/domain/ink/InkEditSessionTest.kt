package com.nisaba.domain.ink

import com.nisaba.domain.notebook.FakeIdGen
import com.nisaba.domain.notebook.FakeInkStore
import com.nisaba.domain.shared.PageId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InkEditSessionTest {
    private val store = FakeInkStore()
    private val ink = InkUseCases(
        LoadPageInkUseCase(store), CommitStrokeUseCase(store), CommitStrokesUseCase(store), EraseUseCase(store, FakeIdGen()),
        UndoUseCase(store), RedoUseCase(store), EraseAnswerUseCase(store),
    )
    private val session = InkEditSession(ink)
    private val a = PageId("a"); private val b = PageId("b")
    private val s1 = lineStroke("s1", 0f, 50f, 10f); private val s2 = lineStroke("s2", 0f, 50f, 30f)

    @Test fun anEditForAPageNoLongerOpenLandsOnThatPageAndTurningBackKeepsUndo() = runTest {
        session.open(a)
        assertTrue(session.editAny(a) { ink.commit(it, s1) })
        session.open(b)
        assertFalse(session.editAny(a) { ink.commit(it, s2) }, "a late edit for page a")
        assertTrue(session.open!!.ink.strokes.isEmpty(), "never on the open page b")
        val back = session.open(a)
        assertEquals(listOf(s1, s2), back.ink.strokes)
        assertTrue(session.editOpen(a) { if (it.canUndo) ink.undo(it) else null })
        assertEquals(listOf(s1), session.open!!.ink.strokes)
    }

    @Test fun editOpenDoesNothingForAnotherPage() = runTest {
        session.open(a)
        assertFalse(session.editOpen(b) { ink.commit(it, s1) })
        assertFalse(session.editOpen(a) { null })
    }

    @Test fun preloadKeepsOnlyNeighboursAndStopsWhenTheReaderMovedOn() = runTest {
        store.saved[b] = listOf(s1)
        session.open(a)
        session.preload(setOf(a, b)) { true }
        store.saved[b] = emptyList() // already at hand: opening b doesn't read the file again
        assertEquals(listOf(s1), session.open(b).ink.strokes)
        session.preload(setOf(PageId("c"))) { false }
        store.saved[a] = listOf(s2) // a was dropped from the neighbours: it is read again
        assertEquals(listOf(s2), session.open(a).ink.strokes)
    }
}
