package com.nisaba.infra.document

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.IdGen
import com.nisaba.domain.shared.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DocumentImportTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val root = File(ctx.cacheDir, "doc-test")
    private val docs = File(root, "docs"); private val rasters = File(root, "raster")
    private var n = 0
    private val ids = object : IdGen { override fun next() = "doc${++n}" }
    private lateinit var store: FileDocumentStore
    private lateinit var cache: DocumentRasterCache

    @Before fun setUp() {
        PdfBoxClient.init(ctx)
        root.deleteRecursively(); root.mkdirs()
        store = FileDocumentStore(docs, rasters, ctx.contentResolver, ids)
        cache = DocumentRasterCache(docs, rasters)
    }

    @After fun tearDown() { root.deleteRecursively() }

    /** A real PDF made on the device: page 1 portrait A4, page 2 landscape, each with a sentence. */
    private fun makePdf(name: String = "Sample Report.pdf"): Uri {
        val pdf = PdfDocument()
        val paint = Paint().apply { color = Color.BLACK; textSize = 28f }
        fun page(no: Int, w: Int, h: Int, text: String) {
            val p = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, no).create())
            p.canvas.drawText(text, 60f, 120f, paint)
            pdf.finishPage(p)
        }
        page(1, 595, 842, "Hello Nisaba first page about Oslo")
        page(2, 842, 595, "Second page mentions the fjords")
        val f = File(root, name)
        f.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return Uri.fromFile(f)
    }

    @Test fun importReadsPageSizesTitleAndText() = runBlocking {
        val progress = mutableListOf<Float>()
        val result = store.import(makePdf().toString()) { progress += it }
        val info = (result as Outcome.Ok).value
        assertEquals("Sample Report", info.title)
        assertEquals(2, info.pageCount)
        assertEquals(1860f, info.pageSizes[0].w, 0.5f)
        assertEquals(1860f * 842 / 595, info.pageSizes[0].h, 2f)
        assertEquals(1860f * 595 / 842, info.pageSizes[1].h, 2f)              // the landscape page is shorter
        assertTrue(info.hasText)
        assertEquals(listOf(0.5f, 1f), progress)
        val text = store.pages(info.id)
        assertTrue("page 1 text: ${text[0]}", text[0].contains("Nisaba") && text[0].contains("Oslo"))
        assertTrue("page 2 text: ${text[1]}", text[1].contains("fjords"))
    }

    @Test fun textSurvivesANewStoreInstanceBecauseItIsOnDisk() = runBlocking {
        val info = (store.import(makePdf().toString()) {} as Outcome.Ok).value
        val again = FileDocumentStore(docs, rasters, ctx.contentResolver, ids)
        assertTrue(again.pages(info.id)[1].contains("fjords"))
    }

    @Test fun pagesRenderAtFullWidthAndAreNotBlankOrBlack() = runBlocking {
        val info = (store.import(makePdf().toString()) {} as Outcome.Ok).value
        val bmp = cache.ensure(info.id, 0)
        assertNotNull(bmp)
        assertEquals(1860, bmp!!.width)
        assertEquals(info.pageSizes[0].h, bmp.height.toFloat(), 2f)                  // picture matches the notebook page shape
        var dark = 0; var light = 0
        for (x in 0 until bmp.width step 8) for (y in 0 until bmp.height step 8) {
            if ((android.graphics.Color.red(bmp.getPixel(x, y))) < 100) dark++ else light++
        }
        assertTrue("some ink on the page ($dark dark)", dark > 20)
        assertTrue("mostly white paper ($light light vs $dark dark)", light > dark * 20)
    }

    @Test fun renderedPagesAreCachedOnDiskAndReadableWithoutRendering() = runBlocking {
        val info = (store.import(makePdf().toString()) {} as Outcome.Ok).value
        assertEquals(null, cache.cached(info.id, 1))
        cache.ensure(info.id, 1)
        assertTrue(File(File(rasters, info.id.value), "1.webp").exists())
        assertNotNull(DocumentRasterCache(docs, rasters).ensure(info.id, 1))           // a fresh cache finds it on disk
    }

    @Test fun deleteRemovesTheFileTextAndPictures() = runBlocking {
        val info = (store.import(makePdf().toString()) {} as Outcome.Ok).value
        cache.ensure(info.id, 0)
        store.delete(info.id)
        assertTrue(docs.listFiles().orEmpty().none { it.name.startsWith(info.id.value) })
        assertFalse(File(rasters, info.id.value).exists())
        assertTrue(store.pages(info.id).isEmpty())
    }

    @Test fun aFileThatIsNotAPdfFailsCleanlyWithoutLeavingFiles() = runBlocking {
        val bad = File(root, "notes.pdf").apply { writeText("this is not a pdf") }
        val result = store.import(Uri.fromFile(bad).toString()) {}
        assertTrue(result is Outcome.Err && result.error is DomainError.Storage)
        assertTrue(docs.listFiles().orEmpty().isEmpty())
    }
}
