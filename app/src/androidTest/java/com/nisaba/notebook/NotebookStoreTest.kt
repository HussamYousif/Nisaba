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
 * save() writes on the store's executor (audit P1-3), so load-after-save
 * assertions poll for the write to land.
 */
@RunWith(AndroidJUnit4::class)
class NotebookStoreTest {

    private lateinit var store: NotebookStore

    @Before
    fun setUp() {
        store = NotebookStore(ApplicationProvider.getApplicationContext())
    }

    private fun waitFor(condition: () -> Boolean, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
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
        store.save(nb) // async write — poll for it below

        val loaded = waitFor(
            { store.load(nb.id).pages.size == 2 },
            10_000,
        ).let { if (it) store.load(nb.id) else null }
        assertTrue("save never landed", loaded != null)
        assertTrue(loaded!!.title.isNotBlank()) // auto title; exact number depends on shelf state
        assertEquals(2, loaded.pages.size)
        assertEquals(1, loaded.pages[0].strokes.size)
        assertEquals(2, loaded.pages[0].strokes[0].points.size)
        assertEquals(0.8f, loaded.pages[0].strokes[0].points[1].p)
        assertEquals(1, loaded.lastOpenedPage)
        assertTrue(store.thumbnailFile(nb.id).exists())
    }

    @Test
    fun debouncedSave_skipsThumbnail_explicitSaveRewritesIt() {
        // Audit P1-3: the 800 ms autosave must not decode/encode a bitmap per
        // pause; only explicit saves (saveNow) rewrite thumb.png.
        val nb = store.createBlank()
        val thumb = store.thumbnailFile(nb.id)
        assertTrue(thumb.exists())
        val mtime = thumb.lastModified()
        val oldUpdated = nb.updatedAt // createBlank's timestamp (save() mutates it)

        // Debounced autosave (thumbnail = false): json lands, thumb untouched.
        store.save(nb)
        val landed = waitFor(
            { store.load(nb.id).updatedAt != oldUpdated },
            10_000,
        )
        assertTrue("autosave never landed", landed)
        Thread.sleep(150) // give any (buggy) thumbnail write time to appear
        assertEquals("debounced save must not rewrite thumb.png", mtime, thumb.lastModified())

        // Explicit save (thumbnail = true): thumb.png is rewritten.
        Thread.sleep(20) // ensure lastModified granularity
        store.saveNow(nb)
        assertTrue("explicit save must rewrite thumb.png", thumb.lastModified() > mtime)
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

    @Test
    fun missingJson_recoversFromTheTmpFile() {
        // Audit P0-6: a crash between fsync and rename leaves notebook.json
        // missing while notebook.json.tmp holds the complete write — load()
        // must recover it instead of failing.
        val nb = store.createBlank()
        nb.title = "Recovered"
        store.saveNow(nb) // synchronous — the crash window is deterministic

        val dir = store.dirOf(nb.id)
        val json = java.io.File(dir, "notebook.json")
        val tmp = java.io.File(dir, "notebook.json.tmp")
        val good = json.readText()
        json.delete()
        tmp.writeText(good) // simulate the crash window: only the tmp survives

        val recovered = store.load(nb.id)
        assertEquals("Recovered", recovered.title)
        assertTrue(store.list().any { it.id == nb.id })
    }

    @Test
    fun save_failureLeavesTheGoodCopyIntact() {
        // Audit P0-6: the old rename fallback deleted notebook.json to retry
        // a rename that just failed — a second failure would lose the
        // notebook entirely. A failed save must throw and leave the last
        // known-good file untouched.
        val nb = store.createBlank()
        store.saveNow(nb)
        val dir = store.dirOf(nb.id)
        val json = java.io.File(dir, "notebook.json")
        val good = json.readText()

        // Force the write to fail after the good file exists: the tmp path is
        // now a directory, so the tmp write throws (EISDIR) before any rename.
        val tmp = java.io.File(dir, "notebook.json.tmp")
        tmp.delete()
        tmp.mkdirs()

        try {
            store.saveNow(nb)
            // On filesystems where this somehow succeeds, the good file must
            // still be present and parseable below.
        } catch (_: Exception) {
            // expected — the failure must not have touched notebook.json
        }
        assertTrue("the good copy must survive a failed save", json.exists())
        assertEquals("the good copy must be unchanged", good, json.readText())
    }
}
