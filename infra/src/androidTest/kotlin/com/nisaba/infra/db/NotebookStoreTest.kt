package com.nisaba.infra.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.domain.notebook.Notebook
import com.nisaba.domain.notebook.Page
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.shared.DocumentId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageSize
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotebookStoreTest {
    private lateinit var db: NisabaDatabase
    private lateinit var store: RoomNotebookStore
    private val nb = Notebook(NotebookId("n1"), "Maths", 1, 2, DocumentId("d1"), true, 42)

    private fun page(id: String, pos: Double, bg: PageBackground = PageBackground.Blank) =
        Page(PageId(id), nb.id, pos, PageSize.PORTRAIT, bg)

    @Before fun setUp() {
        db = NisabaDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomNotebookStore(db.notebooks())
    }

    @After fun tearDown() { db.close() }

    @Test fun createReadRoundTripKeepsPageBackgroundsAndOrder() = runBlocking {
        store.create(nb, listOf(page("p2", 2048.0), page("p1", 1024.0, PageBackground.DocumentPage(DocumentId("d1"), 0))))
        val got = store.get(nb.id)!!
        assertEquals(nb, got.notebook)
        assertEquals(listOf("p1", "p2"), got.pages.map { it.id.value })
        assertEquals(PageBackground.DocumentPage(DocumentId("d1"), 0), got.pages[0].background)
        assertEquals(PageBackground.Blank, got.pages[1].background)
    }

    @Test fun summariesCountPagesAndFlagDocuments() = runBlocking {
        store.create(nb, listOf(page("p1", 1.0), page("p2", 2.0)))
        val s = store.observeAll().first().single()
        assertEquals(2, s.pageCount); assertTrue(s.hasDocument); assertEquals("Maths", s.name)
    }

    @Test fun renameListeningAndReposition() = runBlocking {
        store.create(nb, listOf(page("p1", 1.0), page("p2", 2.0)))
        store.rename(nb.id, "Physics", 99); store.setListening(nb.id, false)
        store.repositionPages(nb.id, listOf(PageId("p1") to 3.0))
        val got = store.get(nb.id)!!
        assertEquals("Physics", got.notebook.name); assertEquals(99L, got.notebook.updatedAtMillis)
        assertEquals(false, got.notebook.listening)
        assertEquals(listOf("p2", "p1"), got.pages.map { it.id.value })
    }

    @Test fun insertPageAndObserveEmitsUpdates() = runBlocking {
        store.create(nb, listOf(page("p1", 1.0)))
        store.insertPage(page("p2", 2.0))
        assertEquals(2, store.observe(nb.id).first()!!.pages.size)
    }

    @Test fun deleteCascadesToPages() = runBlocking {
        store.create(nb, listOf(page("p1", 1.0)))
        store.delete(nb.id)
        assertNull(store.get(nb.id))
        assertTrue(db.notebooks().pages(nb.id.value).isEmpty())
        assertTrue(store.allNames().isEmpty())
    }
}
