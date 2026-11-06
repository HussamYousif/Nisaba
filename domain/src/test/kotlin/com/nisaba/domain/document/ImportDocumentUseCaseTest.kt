package com.nisaba.domain.document

import com.nisaba.domain.notebook.CreateNotebookUseCase
import com.nisaba.domain.notebook.FakeClock
import com.nisaba.domain.notebook.FakeIdGen
import com.nisaba.domain.notebook.FakeNotebookStore
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.shared.DocumentId
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.Outcome
import com.nisaba.domain.shared.PageSize
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ImportDocumentUseCaseTest {
    private val store = FakeNotebookStore()
    private val create = CreateNotebookUseCase(store, FakeClock(), FakeIdGen())

    private class FakeImporter(val result: Outcome<DocumentInfo>) : DocumentImporter {
        val progress = mutableListOf<Float>()
        override suspend fun import(source: String, onProgress: (Float) -> Unit): Outcome<DocumentInfo> {
            onProgress(0.5f); onProgress(1f); return result
        }
    }

    @Test fun anImportedPdfBecomesANotebookWithOnePagePerDocumentPage() = runTest {
        val sizes = listOf(PageSize(1860f, 2400f), PageSize(1860f, 1300f), PageSize(1860f, 2400f))
        val info = DocumentInfo(DocumentId("d1"), "Thesis", sizes, hasText = true)
        val progress = mutableListOf<Float>()
        val result = ImportDocumentUseCase(FakeImporter(Outcome.Ok(info)), create)("content://x", onProgress = progress::add)
        val nb = (result as Outcome.Ok).value
        val pages = store.get(nb.id)!!.pages
        assertEquals("Thesis", nb.name)
        assertEquals(DocumentId("d1"), nb.documentId)
        assertEquals(3, pages.size)
        assertEquals(listOf(2400f, 1300f, 2400f), pages.map { it.size.h })
        assertEquals(PageBackground.DocumentPage(DocumentId("d1"), 2), pages[2].background)
        assertEquals(listOf(0.5f, 1f), progress)
    }

    @Test fun aLessonNotebookKeepsThePdfAsContextAndStartsOnOneBlankPage() = runTest {
        val info = DocumentInfo(DocumentId("d1"), "Thesis", listOf(PageSize(1860f, 2400f), PageSize(1860f, 2400f)), hasText = true)
        val nb = (ImportDocumentUseCase(FakeImporter(Outcome.Ok(info)), create)("x", teachGoal = "  exam on ch. 3  ") as Outcome.Ok).value
        assertEquals(DocumentId("d1"), nb.documentId)
        assertEquals("exam on ch. 3", nb.teachGoal)
        assertEquals(listOf(PageBackground.Blank), store.get(nb.id)!!.pages.map { it.background })
    }

    @Test fun theImportedNotebookAnswersInThePickedVoice() = runTest {
        val info = DocumentInfo(DocumentId("d1"), "Thesis", listOf(PageSize(1860f, 2400f)), hasText = true)
        val result = ImportDocumentUseCase(FakeImporter(Outcome.Ok(info)), create)("x", teachGoal = "", personaId = "teacher")
        val nb = (result as Outcome.Ok).value
        assertEquals("teacher", nb.personaId)
        assertEquals("teacher", store.get(nb.id)!!.notebook.personaId)
    }

    @Test fun aFailedImportCreatesNoNotebook() = runTest {
        val result = ImportDocumentUseCase(FakeImporter(Outcome.Err(DomainError.Invalid("password-protected"))), create)("x")
        assertIs<Outcome.Err>(result)
        assertEquals(emptyList(), store.allNames())
    }
}
