package com.nisaba.domain.notebook

import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.shared.DocumentId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.Outcome
import com.nisaba.domain.shared.PageSize
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NotebookUseCasesTest {
    private val store = FakeNotebookStore()
    private val ids = FakeIdGen()
    private val clock = FakeClock()

    @Test fun createMakesOneBlankPage() = runTest {
        val nb = CreateNotebookUseCase(store, clock, ids)("Maths")
        val got = store.get(nb.id)!!
        assertEquals("Maths", got.notebook.name)
        assertEquals(1, got.pages.size)
        assertEquals(PageBackground.Blank, got.pages[0].background)
    }

    @Test fun createEndlessMakesOneEndlessCanvas() = runTest {
        val nb = CreateNotebookUseCase(store, clock, ids)("Sketches", endless = true)
        val pages = store.get(nb.id)!!.pages
        assertEquals(1, pages.size)
        assertEquals(true, pages[0].size.isEndless)
        assertEquals(false, PageSize.PORTRAIT.isEndless)
    }

    @Test fun createFromDocumentMakesOnePagePerSourcePage() = runTest {
        val doc = DocumentId("d1")
        val nb = CreateNotebookUseCase(store, clock, ids)("Paper", doc, listOf(PageSize(1860f, 2400f), PageSize(1860f, 2500f)))
        val pages = store.get(nb.id)!!.pages
        assertEquals(listOf(PageBackground.DocumentPage(doc, 0), PageBackground.DocumentPage(doc, 1)), pages.map { it.background })
        assertEquals(2500f, pages[1].size.h)
    }

    @Test fun aNotebookKeepsItsVoiceAndTheVoiceCanChange() = runTest {
        assertEquals(PersonaRegistry.DEFAULT_ID, CreateNotebookUseCase(store, clock, ids)("Plain old").personaId)
        val nb = CreateNotebookUseCase(store, clock, ids)("Underworld", personaId = "zagreus")
        assertEquals("zagreus", store.get(nb.id)!!.notebook.personaId)
        SetPersonaUseCase(store)(nb.id, "teacher")
        assertEquals("teacher", store.get(nb.id)!!.notebook.personaId)
        SetPersonaUseCase(store)(nb.id, "no-such-voice")
        assertEquals(PersonaRegistry.DEFAULT_ID, store.get(nb.id)!!.notebook.personaId)
        assertEquals(PersonaRegistry.DEFAULT_ID, CreateNotebookUseCase(store, clock, ids)("Odd", personaId = "gone").personaId)
    }

    @Test fun renameKeepsOwnNameAvailableButDedupesBlank() = runTest {
        val nb = CreateNotebookUseCase(store, clock, ids)("Untitled")
        assertEquals("Untitled", RenameNotebookUseCase(store, clock)(nb.id, "  "))
        assertEquals("Renamed", RenameNotebookUseCase(store, clock)(nb.id, "Renamed"))
    }

    @Test fun deleteRemovesRowBeforeFilesAndCleansDocument() = runTest {
        val log = mutableListOf<String>()
        val ink = FakeInkStore(log); val docs = FakeDocumentStore()
        val nb = CreateNotebookUseCase(store, clock, ids)("Paper", DocumentId("d1"), listOf(PageSize.PORTRAIT))
        DeleteNotebookUseCase(store, ink, docs)(nb.id)
        assertEquals(null, store.get(nb.id))
        assertEquals(1, ink.deleted.size)
        assertEquals(listOf(DocumentId("d1")), docs.deleted)
        assertEquals(listOf("create", "deleteRow"), store.log)
        assertEquals(listOf("deleteFiles"), log)
    }

    @Test fun insertPageOrdersHeadMiddleTail() = runTest {
        val nb = CreateNotebookUseCase(store, clock, ids)("N")
        val first = store.get(nb.id)!!.pages[0]
        val insert = InsertPageUseCase(store, ids)
        val tail = (insert(nb.id, null) as Outcome.Ok).value
        val mid = (insert(nb.id, first.id) as Outcome.Ok).value
        val order = store.get(nb.id)!!.pages.map { it.id }
        assertEquals(listOf(first.id, mid.id, tail.id), order)
    }

    @Test fun insertRebalancesWhenGapCollapses() = runTest {
        val nb = CreateNotebookUseCase(store, clock, ids)("N")
        val first = store.get(nb.id)!!.pages[0]
        val insert = InsertPageUseCase(store, ids)
        insert(nb.id, null)
        repeat(70) { assertIs<Outcome.Ok<Page>>(insert(nb.id, first.id)) }
        val positions = store.get(nb.id)!!.pages.map { it.position }
        assertEquals(72, positions.size)
        assertEquals(positions.size, positions.toSet().size, "positions stay unique")
        assertEquals(positions.sorted(), positions)
    }

    @Test fun insertIntoMissingNotebookFails() = runTest {
        assertIs<Outcome.Err>(InsertPageUseCase(store, ids)(NotebookId("nope"), null))
    }
}
