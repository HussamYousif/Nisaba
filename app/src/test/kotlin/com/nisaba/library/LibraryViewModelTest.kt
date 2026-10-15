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
import com.nisaba.domain.notebook.RenameNotebookUseCase
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.settings.SettingsStore
import com.nisaba.domain.shared.DocumentId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.Outcome
import com.nisaba.domain.shared.PageSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
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
        vm.selectVoice("zagreus")
        vm.confirmVoice { error("a canvas needs no PDF") }
        assertEquals(LibraryDialog.NameIt(NotebookKind.Canvas, "zagreus"), vm.state.value.dialog)
        vm.back(); assertEquals(LibraryDialog.ChooseVoice(NotebookKind.Canvas, "zagreus"), vm.state.value.dialog)
        vm.confirmVoice {}
        vm.confirmCreate("Underworld", opened::add); runCurrent()
        val nb = store.get(opened.single())!!
        assertEquals("zagreus", nb.notebook.personaId)
        assertTrue(nb.pages.single().size.isEndless)
        assertEquals("zagreus", settings.flow.value.lastPersona)
        assertEquals(LibraryDialog.None, vm.state.value.dialog)
        assertEquals("zagreus", vm.state.value.notebooks.single().personaId)
    }

    @Test fun aPdfGoesThroughThePickerInTheVoicePicked() = runTest {
        val vm = vm()
        var picker = 0
        vm.onNew(); vm.chooseKind(NotebookKind.Pdf); vm.selectVoice("flirt")
        vm.confirmVoice { picker++ }
        assertEquals(1, picker); assertEquals(LibraryDialog.None, vm.state.value.dialog)
        vm.onPdfPicked("x.pdf", opened::add); runCurrent()
        val nb = store.get(opened.single())!!.notebook
        assertEquals("flirt", nb.personaId); assertEquals(null, nb.teachGoal); assertEquals(DocumentId("d-x.pdf"), nb.documentId)
    }

    @Test fun aPdfToLearnAsksForTheGoalAfterThePicker() = runTest {
        val vm = vm()
        vm.onNew(); vm.chooseKind(NotebookKind.Lesson); vm.selectVoice("yukino"); vm.confirmVoice {}
        vm.onPdfPicked("book.pdf", opened::add)
        assertEquals(LibraryDialog.LearnGoal("book.pdf", "yukino"), vm.state.value.dialog)
        vm.confirmLearn("exam on ch. 3", opened::add); runCurrent()
        val nb = store.get(opened.single())!!.notebook
        assertEquals("yukino", nb.personaId); assertEquals("exam on ch. 3", nb.teachGoal)
    }

    @Test fun aCancelledPickerMakesNothing() = runTest {
        val vm = vm()
        vm.onNew(); vm.chooseKind(NotebookKind.Pdf); vm.confirmVoice {}
        vm.onPdfPicked(null, opened::add); runCurrent()
        vm.onPdfPicked("late.pdf", opened::add); runCurrent() // nothing was being picked any more
        assertTrue(opened.isEmpty()); assertTrue(store.allNames().isEmpty())
        assertEquals(PersonaRegistry.DEFAULT_ID, PersonaRegistry.of("whatever").id)
    }
}
