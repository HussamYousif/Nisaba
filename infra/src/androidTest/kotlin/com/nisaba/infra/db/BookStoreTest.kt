package com.nisaba.infra.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.domain.book.BookIdea
import com.nisaba.domain.book.BookMap
import com.nisaba.domain.book.BookReading
import com.nisaba.domain.book.Concept
import com.nisaba.domain.notebook.Notebook
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookStoreTest {
    private lateinit var db: NisabaDatabase
    private val nb = NotebookId("n1")

    @Before fun setUp() {
        db = NisabaDatabase.inMemory(ApplicationProvider.getApplicationContext())
        runBlocking { RoomNotebookStore(db.notebooks()).create(Notebook(nb, "Book", 1, 1, null, true, 0, book = true), emptyList()) }
    }

    @After fun tearDown() { db.close() }

    @Test fun aMapBeingReadThenWalkedComesBackAsItWent() = runBlocking {
        val store = RoomBookStore(db.books())
        assertNull(store.get(nb))
        val reading = BookMap("DDIA", reading = BookReading(12, mapOf(3 to listOf(BookIdea("Logs", "Append-only", 61, 70)))))
        store.save(nb, reading)
        assertEquals(reading, store.get(nb))
        val walked = BookMap(
            "DDIA", "Trade-offs.", listOf(Concept("Reliability", "Faults", 1, 40, PageId("p2")), Concept("Replication", "", 41, 90)),
            overviewPageId = PageId("p1"),
        )
        store.save(nb, walked)
        assertEquals(walked, store.observe(nb).first())
        assertEquals(true, RoomNotebookStore(db.notebooks()).get(nb)!!.notebook.book)
    }
}
