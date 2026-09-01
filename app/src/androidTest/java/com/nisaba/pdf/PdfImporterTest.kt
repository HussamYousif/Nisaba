package com.nisaba.pdf

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.notebook.NotebookStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PDF import pipeline (docs/06): copy → render → notebook creation,
 * background cache with re-derivation, and transactional rollback.
 */
@RunWith(AndroidJUnit4::class)
class PdfImporterTest {

    private lateinit var store: NotebookStore
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        store = NotebookStore(ctx)
        tmpDir = File(ctx.filesDir, "pdf-test-tmp").apply { mkdirs() }
    }

    /** Generates a real N-page PDF via android.graphics.pdf.PdfDocument. */
    private fun createPdf(name: String, pages: Int): File {
        val file = File(tmpDir, name)
        val doc = PdfDocument()
        val paint = Paint().apply { color = Color.BLACK; textSize = 18f }
        repeat(pages) { i ->
            val info = PdfDocument.PageInfo.Builder(595, 842, i + 1).create()
            val page = doc.startPage(info)
            page.canvas.drawText("Nisaba test page ${i + 1}", 72f, 100f, paint)
            doc.finishPage(page)
        }
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    @Test
    fun import_createsNotebookWithRenderedBackgrounds() {
        val pdf = createPdf("sample.pdf", 3)
        val result = PdfImporter(store).importFrom(FileInputStream(pdf), displayName = "sample.pdf")

        assertEquals(3, result.pageCount)
        val nb = result.notebook
        assertEquals("sample", nb.title)
        assertEquals("pdf", nb.source?.type)
        assertEquals(3, nb.pages.size)
        assertEquals("backgrounds/p0.png", nb.pages[0].background)

        // Page space = PDF page box at 300 dpi (595×842 pt).
        assertEquals(595f * 300f / 72f, nb.pages[0].width.toFloat(), 1f)
        assertEquals(842f * 300f / 72f, nb.pages[0].height.toFloat(), 1f)

        // Backgrounds rendered at 200 dpi, RGB_565 PNGs on disk.
        for (i in 0 until 3) {
            val bg = File(store.dirOf(nb.id), "backgrounds/p$i.png")
            assertTrue("background $i missing", bg.exists())
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(
            File(store.dirOf(nb.id), "backgrounds/p0.png").absolutePath
        )
        assertNotNull(bmp)
        assertEquals(595f * 200f / 72f, bmp!!.width.toFloat(), 1f)
        assertEquals(842f * 200f / 72f, bmp.height.toFloat(), 1f)

        // Pixel content: a document page is mostly white with dark text pixels.
        // (Skips with a warning in environments where PdfRenderer renders black.)
        var dark = 0
        var light = 0
        for (y in 0 until bmp.height step 7) {
            for (x in 0 until bmp.width step 7) {
                val v = bmp.getPixel(x, y)
                val lum = ((v shr 16 and 0xFF) + (v shr 8 and 0xFF) + (v and 0xFF)) / 3
                if (lum < 110) dark++ else if (lum > 190) light++
            }
        }
        PdfTestSupport.assumeRenders(dark, light)
        assertTrue("page should be mostly light (dark=$dark light=$light)", light > dark * 10)
        assertTrue("text must be visible (dark=$dark)", dark > 3)
        bmp.recycle()

        // The PDF itself is copied into the notebook (survives source deletion).
        assertTrue(File(store.dirOf(nb.id), "import.pdf").exists())
    }

    @Test
    fun cache_decodesAndReDerivesMissingBackgrounds() {
        val pdf = createPdf("cache.pdf", 2)
        val result = PdfImporter(store).importFrom(FileInputStream(pdf))
        val dir = store.dirOf(result.notebook.id)

        val cache = PageBackgroundCache(dir, File(dir, "import.pdf"))
        val first = decodeSync(cache, 0)
        assertNotNull(first)
        assertEquals(595f * 200f / 72f, first!!.width.toFloat(), 1f)

        // Delete the PNG: a FRESH cache (cold LRU) must re-derive it from the PDF.
        val png = File(dir, "backgrounds/p0.png")
        assertTrue(png.delete())
        val freshCache = PageBackgroundCache(dir, File(dir, "import.pdf"))
        val second = decodeSync(freshCache, 0)
        assertNotNull(second)
        assertTrue("png not re-persisted", png.exists())
        freshCache.shutdown()

        // A page that was never rendered (out of range) yields null, not crash.
        assertTrue(decodeSync(cache, 99) == null)
        cache.shutdown()
    }

    private fun assertNoOrphanedNotebooks() {
        val notebooksDir = store.dirOf("").parentFile!!
        val orphans = notebooksDir.listFiles()
            ?.filter { it.isDirectory && File(it, PdfImporter.IMPORT_PDF).exists() }
            .orEmpty()
        assertEquals("orphaned notebook dirs: ${orphans.map { it.name }}", 0, orphans.size)
    }

    /** Notebook dirs present before a rollback test runs. */
    private fun existingNotebookDirs(): Set<String> =
        store.dirOf("").parentFile!!.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            .orEmpty()
            .toSet()

    /**
     * Assert the rollback created no NEW notebook dirs. Scoped to what this
     * test could have created — the earlier tests in this class leave real
     * notebooks behind, so a global "zero orphans" assertion only passes by
     * method-ordering luck (audit P3-5).
     */
    private fun assertNoNewNotebookDirs(before: Set<String>) {
        val now = existingNotebookDirs()
        assertEquals("half-created dirs left behind: ${now - before}", before, now)
    }

    @Test
    fun session_pageCount_andPageBox_comeFromPdfBox() {
        // S3 on-device find: the Boox's framework PdfRenderer mis-reports
        // pageCount (a 5-page PDF imported as 1 page, silently dropping the
        // rest). The session must count and measure via PdfBox — the engine
        // that works on the target device.
        val pdf = createPdf("count.pdf", 4)
        PdfPageRenderer.Session(pdf).use { session ->
            assertEquals(4, session.pageCount)
            val (w, h) = session.pageBox(2)
            assertEquals(595, w)
            assertEquals(842, h)
        }
    }

    @Test
    fun import_failureRollsBackCleanly() {
        val pdf = createPdf("boom.pdf", 5)
        val before = existingNotebookDirs()
        // A stream that dies after 100 bytes simulates a corrupt/cut source.
        val broken = object : InputStream() {
            private val inner = FileInputStream(pdf)
            private var read = 0
            override fun read(): Int {
                if (read++ > 100) throw java.io.IOException("source died")
                return inner.read()
            }
        }
        var threw = false
        try {
            PdfImporter(store).importFrom(broken)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("import should have thrown", threw)
        assertNoNewNotebookDirs(before) // transactional: nothing half-created remains
    }

    @Test
    fun import_cancelLeavesNothingBehind() {
        val pdf = createPdf("cancel.pdf", 4)
        val before = existingNotebookDirs()
        var cancelled = false
        try {
            PdfImporter(store).importFrom(
                FileInputStream(pdf),
                cancelled = { true }, // cancel immediately, before rendering
            )
        } catch (_: PdfImporter.ImportCancelled) {
            cancelled = true
        }
        assertTrue("import should have been cancelled", cancelled)
        assertNoNewNotebookDirs(before)
    }

    private fun decodeSync(cache: PageBackgroundCache, index: Int): android.graphics.Bitmap? {
        val latch = CountDownLatch(1)
        var out: android.graphics.Bitmap? = null
        cache.get(index) { bmp ->
            out = bmp
            latch.countDown()
        }
        assertTrue("cache.get timed out", latch.await(10, TimeUnit.SECONDS))
        return out
    }
}
