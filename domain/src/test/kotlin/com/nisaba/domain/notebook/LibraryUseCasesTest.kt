package com.nisaba.domain.notebook

import com.nisaba.domain.ink.lineStroke
import com.nisaba.domain.shared.Outcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LibraryUseCasesTest {
    private val store = FakeNotebookStore()
    private val ink = FakeInkStore()
    private val clock = FakeClock()
    private val create = CreateNotebookUseCase(store, clock, FakeIdGen("n"))

    @Test fun pinnedFirstThenRecentAndTheTrashKeptApartUntilItEmpties() = runTest {
        val a = create("A"); clock.now += 10; val b = create("B"); clock.now += 10; val c = create("C")
        PinNotebookUseCase(store)(a.id, true)
        assertEquals(listOf("A", "C", "B"), ListNotebooksUseCase(store)().first().map { it.name })
        TrashNotebookUseCase(store, clock)(c.id)
        assertEquals(listOf("A", "B"), ListNotebooksUseCase(store)().first().map { it.name })
        assertEquals(listOf("C"), ListTrashUseCase(store)().first().map { it.name })
        RestoreNotebookUseCase(store)(c.id)
        assertTrue(ListTrashUseCase(store)().first().isEmpty())
        TrashNotebookUseCase(store, clock)(b.id)
        val empty = EmptyTrashUseCase(store, DeleteNotebookUseCase(store, ink, FakeDocumentStore()), clock)
        assertEquals(0, empty()) // not 30 days yet
        clock.now += (TRASH_DAYS + 1) * 24L * 60 * 60 * 1000
        assertEquals(1, empty())
        assertEquals(null, store.get(b.id))
    }

    @Test fun pagesCanBeDeletedMovedAndDuplicatedButTheLastStays() = runTest {
        val nb = create("Pages")
        val insert = InsertPageUseCase(store, FakeIdGen("p"))
        val first = store.get(nb.id)!!.pages.single()
        val second = (insert(nb.id, first.id) as Outcome.Ok).value
        ink.save(first.id, listOf(lineStroke("s1", 100f, 200f, 300f)))
        val copy = (DuplicatePageUseCase(insert, ink, FakeIdGen("c"))(nb.id, first) as Outcome.Ok).value
        assertEquals(listOf(first.id, copy.id, second.id), store.get(nb.id)!!.pages.sortedBy { it.position }.map { it.id })
        assertEquals("c1", ink.saved[copy.id]!!.single().id.value) // the ink copied, with its own ids
        MovePageUseCase(store)(nb.id, second.id, -1)
        assertEquals(listOf(first.id, second.id, copy.id), store.get(nb.id)!!.pages.sortedBy { it.position }.map { it.id })
        assertIs<Outcome.Err>(MovePageUseCase(store)(nb.id, first.id, -1))
        DeletePageUseCase(store, ink)(nb.id, copy.id)
        DeletePageUseCase(store, ink)(nb.id, second.id)
        assertTrue(copy.id in ink.deleted)
        assertIs<Outcome.Err>(DeletePageUseCase(store, ink)(nb.id, first.id)) // a notebook keeps one page
    }
}
