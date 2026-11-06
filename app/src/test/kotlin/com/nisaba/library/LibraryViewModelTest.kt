package com.nisaba.library

import com.nisaba.domain.document.DocumentImporter
import com.nisaba.domain.document.DocumentInfo
import com.nisaba.domain.document.ImportDocumentUseCase
import com.nisaba.domain.notebook.CreateNotebookUseCase
import com.nisaba.domain.notebook.DeleteNotebookUseCase
import com.nisaba.domain.notebook.FakeClock
import com.nisaba.domain.notebook.FakeDocumentStore
import com.nisaba.domain.notebook.FakeIdGen
import com.nisaba.domain.notebook.FakeInkStore
import com.nisaba.domain.notebook.FakeNotebookStore
import com.nisaba.domain.notebook.ListNotebooksUseCase
import com.nisaba.domain.notebook.NotebookStore
import com.nisaba.domain.notebook.NotebookSummary
import com.nisaba.domain.notebook.RenameNotebookUseCase
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.settings.SettingsStore
import com.nisaba.domain.shared.DocumentId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.Outcome
import com.nisaba.domain.shared.PageSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val store = FakeNotebookStore()
    private val create = CreateNotebookUseCase(store, FakeClock(), FakeIdGen("n"))
    private val settings = object : SettingsStore {
        val flow = MutableStateFlow(Settings(lastPersona = "teacher"))
        override fun observe() = flow
        override suspend fun update(transform: (Settings) -> Settings) { flow.value = transform(flow.value) }
    }
    private val importer = object : DocumentImporter {
        override suspend fun import(source: String, onProgress: (Float) -> Unit) =
            Outcome.Ok(DocumentInfo(DocumentId("d-$source"), "Thesis", listOf(PageSize(1860f, 2400f)), hasText = true))
    }
    private val opened = mutableListOf<NotebookId>()

    private fun TestScope.vm(): LibraryViewModel {
        val vm = LibraryViewModel(
            ListNotebooksUseCase(store), create, RenameNotebookUseCase(store, FakeClock()),
            DeleteNotebookUseCase(store, FakeInkStore(), FakeDocumentStore()), ImportDocumentUseCase(importer, create),
            settings, backgroundScope,
        )
        runCurrent()
        return vm
    }

    @Test fun aNotebookIsKindThenVoiceThenNameAndTheVoiceIsRemembered() = runTest {
        val vm = vm()
        vm.onNew(); assertEquals(LibraryDialog.ChooseKind, vm.state.value.dialog)
        vm.chooseKind(NotebookKind.Canvas)
        assertEquals(LibraryDialog.ChooseVoice(NotebookKind.Canvas, "teacher"), vm.state.value.dialog) // starts at the last one
        vm.selectVoice("writer")
        vm.confirmVoice { error("a canvas needs no PDF") }
        assertEquals(LibraryDialog.NameIt(NotebookKind.Canvas, "writer"), vm.state.value.dialog)
        vm.back(); assertEquals(LibraryDialog.ChooseVoice(NotebookKind.Canvas, "writer"), vm.state.value.dialog)
        vm.confirmVoice {}
        vm.confirmCreate("Prague", opened::add); runCurrent()
        val nb = store.get(opened.single())!!
        assertEquals("writer", nb.notebook.personaId)
        assertTrue(nb.pages.single().size.isEndless)
        assertEquals("writer", settings.flow.value.lastPersona)
        assertEquals(LibraryDialog.None, vm.state.value.dialog)
        assertEquals("writer", vm.state.value.notebooks.single().personaId)
    }

    @Test fun aPdfGoesThroughThePickerInTheVoicePicked() = runTest {
        val vm = vm()
        var picker = 0
        vm.onNew(); vm.chooseKind(NotebookKind.Pdf); vm.selectVoice("teacher")
        vm.confirmVoice { picker++ }
        assertEquals(1, picker); assertEquals(LibraryDialog.None, vm.state.value.dialog)
        vm.onPdfPicked("x.pdf", opened::add); runCurrent()
        val nb = store.get(opened.single())!!.notebook
        assertEquals("teacher", nb.personaId); assertEquals(null, nb.teachGoal); assertEquals(DocumentId("d-x.pdf"), nb.documentId)
    }

    @Test fun aPdfToLearnBecomesABookWalkStraightAfterThePicker() = runTest {
        val vm = vm()
        vm.onNew(); vm.chooseKind(NotebookKind.Book); vm.selectVoice("teacher"); vm.confirmVoice {}
        vm.onPdfPicked("book.pdf", opened::add); runCurrent()
        val nb = store.get(opened.single())!!.notebook
        assertEquals("teacher", nb.personaId); assertTrue(nb.book); assertEquals(null, nb.teachGoal)
    }

    @Test fun aCancelledPickerMakesNothing() = runTest {
        val vm = vm()
        vm.onNew(); vm.chooseKind(NotebookKind.Pdf); vm.confirmVoice {}
        vm.onPdfPicked(null, opened::add); runCurrent()
        vm.onPdfPicked("late.pdf", opened::add); runCurrent() // nothing was being picked any more
        assertTrue(opened.isEmpty()); assertTrue(store.allNames().isEmpty())
        assertEquals(PersonaRegistry.DEFAULT_ID, PersonaRegistry.of("whatever").id)
    }

    @Test fun theLibraryPagesPinsAndKeepsATrash() = runTest {
        val clock = FakeClock()
        val changes = LibraryChanges(
            com.nisaba.domain.notebook.PinNotebookUseCase(store), com.nisaba.domain.notebook.TrashNotebookUseCase(store, clock),
            com.nisaba.domain.notebook.RestoreNotebookUseCase(store),
            com.nisaba.domain.notebook.EmptyTrashUseCase(store, DeleteNotebookUseCase(store, FakeInkStore(), FakeDocumentStore()), clock),
            com.nisaba.domain.notebook.ListTrashUseCase(store), now = { clock.now },
        )
        repeat(8) { create("N$it") }
        val vm = LibraryViewModel(
            ListNotebooksUseCase(store), create, RenameNotebookUseCase(store, FakeClock()),
            DeleteNotebookUseCase(store, FakeInkStore(), FakeDocumentStore()), ImportDocumentUseCase(importer, create),
            settings, backgroundScope, changes,
        )
        runCurrent()
        assertEquals(2, vm.state.value.gridPages); assertEquals(6, vm.state.value.visible.size)
        vm.gridPage(1); assertEquals(2, vm.state.value.visible.size)
        val last = vm.state.value.notebooks.last()
        vm.pin(last.id, true); runCurrent()
        assertEquals(last.id, vm.state.value.notebooks.first().id) // pinned to the front
        vm.onDeleteRequested(last.id, last.name); runCurrent()
        assertEquals(listOf(last.id), vm.state.value.trash.map { it.id })
        assertEquals(7, vm.state.value.notebooks.size)
        vm.restore(last.id); runCurrent()
        assertTrue(vm.state.value.trash.isEmpty())
        vm.offerIncomingPdf("handed.pdf")
        assertEquals(NotebookKind.Pdf, (vm.state.value.dialog as LibraryDialog.ChooseVoice).kind)
        vm.confirmVoice(opened::add) { error("no system picker for a PDF handed over") }; runCurrent()
        assertEquals("Thesis", store.get(opened.single())!!.notebook.name)
    }

    @Test fun theDebugCleanupWaitsForTheListToLoad() = runTest {
        create("check-1"); create("check-2"); create("Keep")
        val slow = object : NotebookStore by store { // Room answers after the first frame
            override fun observeAll(): Flow<List<NotebookSummary>> = flow { delay(10); emitAll(store.observeAll()) }
        }
        val vm = LibraryViewModel(
            ListNotebooksUseCase(slow), create, RenameNotebookUseCase(store, FakeClock()),
            DeleteNotebookUseCase(store, FakeInkStore(), FakeDocumentStore()), ImportDocumentUseCase(importer, create),
            settings, backgroundScope,
        )
        vm.debugDeleteNamed("check-") // asked for before the list is in
        advanceTimeBy(20); runCurrent() // background work: advanceUntilIdle would not wait for it
        assertEquals(listOf("Keep"), vm.state.value.notebooks.map { it.name })
    }

    @Test fun timesReadLikeAPersonSaysThem() {
        val h = 60 * 60 * 1000L
        assertEquals("just now", ago(0, 10_000))
        assertEquals("5 min ago", ago(0, 5 * 60_000))
        assertEquals("3 h ago", ago(0, 3 * h))
        assertEquals("yesterday", ago(0, 30 * h))
        assertEquals("4 days ago", ago(0, 4 * 24 * h))
    }
}
