package com.nisaba.notebook

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.notebook.model.Notebook
import com.nisaba.notebook.model.NotebookPage
import com.nisaba.notebook.model.Stroke
import com.nisaba.notebook.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Notebook persistence round-trips (docs/05 §4): create → mutate → save →
 * reload must be lossless, and deletion must remove the directory.
 */
@RunWith(AndroidJUnit4::class)
class NotebookStoreTest {

    private lateinit var store: NotebookStore

    @Before
    fun setUp() {
        store = NotebookStore(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun createSaveLoad_roundTripsLosslessly() {
        val nb = store.createBlank()
        val page = nb.pages.first()
        page.strokes = listOf(
            Stroke(
                id = "s-1",
                points = listOf(
                    StrokePoint(10f, 20f, 0.3f, 0L),
                    StrokePoint(40f, 60f, 0.8f, 120L),
                ),
            )
        )
        nb.pages = nb.pages + NotebookPage(index = 1)
        nb.lastOpenedPage = 1
        store.save(nb)

        val loaded = store.load(nb.id)
        assertTrue(loaded.title.isNotBlank()) // auto title; exact number depends on shelf state
        assertEquals(2, loaded.pages.size)
        assertEquals(1, loaded.pages[0].strokes.size)
        assertEquals(2, loaded.pages[0].strokes[0].points.size)
        assertEquals(0.8f, loaded.pages[0].strokes[0].points[1].p)
        assertEquals(1, loaded.lastOpenedPage)
        assertTrue(store.thumbnailFile(nb.id).exists())
    }

    @Test
    fun listAndDelete_work() {
        val a = store.createBlank()
        val b = store.createBlank()
        assertTrue(store.list().any { it.id == a.id })
        assertTrue(store.list().any { it.id == b.id })

        store.delete(a.id)
        assertNull(store.list().firstOrNull { it.id == a.id })
        assertNotNull(store.list().firstOrNull { it.id == b.id })
    }

    @Test
    fun corruptFile_fallsBackCleanly() {
        val nb = store.createBlank()
        // Truncate the JSON behind the store's back.
        java.io.File(store.dirOf(nb.id), "notebook.json").writeText("{broken")
        // The corrupt notebook must not crash the shelf listing.
        assertTrue(store.list().none { it.id == nb.id })
    }
}
