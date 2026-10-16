package com.nisaba.infra.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.nisaba.domain.shared.DocumentId
import com.nisaba.infra.render.RasterSource
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Page pictures of imported PDFs: rendered on demand with [PdfRenderer] (PdfBox as a fallback when the system renderer
 * produces a black page, which Android 15 does for some files), kept in memory (3 pages) and as WebP on disk.
 */
class DocumentRasterCache(private val docsDir: File, private val rasterDir: File, private val widthPx: Int = PAGE_WIDTH) : RasterSource {
    private val memory = LruCache<String, Bitmap>(MEMORY_PAGES)
    private val lock = Mutex()

    /** The open page's picture, outside the LRU: the page grid or an export must not push it out from under the page. */
    @Volatile private var pinned: Pair<String, Bitmap>? = null

    private fun key(id: DocumentId, index: Int) = "${id.value}/$index"
    private fun file(id: DocumentId, index: Int) = File(File(rasterDir, id.value), "$index.webp")

    /** Memory only, so it is safe on the UI thread; [ensure] brings a page in from disk or renders it. */
    override fun cached(documentId: DocumentId, pageIndex: Int): Bitmap? {
        val k = key(documentId, pageIndex)
        return pinned?.takeIf { it.first == k }?.second ?: memory[k]
    }

    /** Keeps page [pageIndex]'s picture (if it is in) for as long as it is the open page. */
    fun pin(documentId: DocumentId, pageIndex: Int) {
        val k = key(documentId, pageIndex)
        memory[k]?.let { pinned = k to it }
    }

    private fun fromDisk(documentId: DocumentId, pageIndex: Int): Bitmap? {
        val f = file(documentId, pageIndex)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path)?.also { memory.put(key(documentId, pageIndex), it) }
    }

    /** Renders (if needed) and returns the picture of a page; null if the file cannot be rendered at all. */
    suspend fun ensure(documentId: DocumentId, pageIndex: Int): Bitmap? = withContext(Dispatchers.IO) {
        cached(documentId, pageIndex)?.let { return@withContext it }
        var fresh = false
        val bmp = lock.withLock {
            cached(documentId, pageIndex) ?: fromDisk(documentId, pageIndex)
                ?: render(documentId, pageIndex)?.also { memory.put(key(documentId, pageIndex), it); fresh = true }
        }
        // kept on disk outside the lock: the next page's render need not wait for this one's encoding
        if (bmp != null && fresh) runCatching { save(bmp, file(documentId, pageIndex)) }
        bmp
    }

    /**
     * A small picture of a page (the page grid): the full one if it is in memory, else decoded small from disk, else
     * rendered small (never stored); a page the system renders black goes the full way, PdfBox and all.
     */
    suspend fun small(documentId: DocumentId, pageIndex: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        cached(documentId, pageIndex)?.let { return@withContext it }
        val f = file(documentId, pageIndex)
        if (f.exists()) return@withContext BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = SMALL_SAMPLE })
        val small = lock.withLock {
            val pdf = File(docsDir, "${documentId.value}.pdf")
            val r = if (pdf.exists()) runCatching { systemRenderer(documentId, pdf) }.getOrNull() else null
            if (r == null || pageIndex !in 0 until r.pageCount) null else runCatching { renderSmall(r, pageIndex, widthPx) }.getOrNull()
        }
        if (small != null && !looksBlack(small)) small else ensure(documentId, pageIndex)
    }

    private fun renderSmall(renderer: PdfRenderer, index: Int, widthPx: Int): Bitmap = renderer.openPage(index).use { page ->
        val h = (page.height * widthPx / page.width.toFloat()).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.WHITE)
            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    private fun render(id: DocumentId, index: Int): Bitmap? {
        val pdf = File(docsDir, "${id.value}.pdf")
        if (!pdf.exists()) return null
        val renderer = runCatching { systemRenderer(id, pdf) }.getOrNull()
        if (renderer != null && index !in 0 until renderer.pageCount) return null // past the end: nothing to render, nothing to retry
        val system = renderer?.let { r -> runCatching { renderWithSystem(r, index) }.getOrNull() }
        return if (system != null && !looksBlack(system)) system else runCatching { renderWithPdfBox(id, pdf, index) }.getOrNull() ?: system
    }

    /** The system renderer of the document being read, kept open (only ever touched under [lock]): opening one per page was slow. */
    private var openSystem: Triple<DocumentId, ParcelFileDescriptor, PdfRenderer>? = null

    private fun systemRenderer(id: DocumentId, pdf: File): PdfRenderer {
        openSystem?.takeIf { it.first == id }?.let { return it.third }
        openSystem?.let { (_, fd, r) -> runCatching { r.close() }; runCatching { fd.close() } }
        openSystem = null
        val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = runCatching { PdfRenderer(fd) }.getOrElse { fd.close(); throw it }
        openSystem = Triple(id, fd, renderer)
        return renderer
    }

    /** The PdfBox fallback's open document (only ever touched under [lock]): parsing a PDF per page was the slow part. */
    private var openDoc: Pair<DocumentId, PDDocument>? = null

    private fun renderWithSystem(renderer: PdfRenderer, index: Int): Bitmap =
        renderer.openPage(index).use { page ->
            val (w, h) = rasterSize(page.width.toFloat(), page.height.toFloat())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }

    private fun renderWithPdfBox(id: DocumentId, pdf: File, index: Int): Bitmap {
        val doc = openDoc?.takeIf { it.first == id }?.second ?: PDDocument.load(pdf).also { d ->
            openDoc?.second?.close()
            openDoc = id to d
        }
        val box = doc.getPage(index).mediaBox
        val (w, _) = rasterSize(box.width, box.height)
        return PDFRenderer(doc).renderImage(index, w / box.width)
    }

    /** The page's picture size: the page's width in pixels, unless a very tall page would make it huge (then capped). */
    private fun rasterSize(pageW: Float, pageH: Float): Pair<Int, Int> {
        val scale = minOf(widthPx / pageW, MAX_HEIGHT_FACTOR * widthPx / pageH)
        return (pageW * scale).toInt().coerceAtLeast(1) to (pageH * scale).toInt().coerceAtLeast(1)
    }

    /** True for a (nearly) all-black render: the sign of the Android 15 PdfRenderer bug. */
    private fun looksBlack(bmp: Bitmap): Boolean {
        var sum = 0L; var n = 0
        for (i in 0 until PROBE) for (j in 0 until PROBE) {
            val c = bmp.getPixel((bmp.width - 1) * i / (PROBE - 1), (bmp.height - 1) * j / (PROBE - 1))
            sum += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3; n++
        }
        return sum / n < BLACK_MEAN
    }

    /** Written aside, then renamed: a kill mid-write never leaves half a page to be shown later. */
    private fun save(bmp: Bitmap, f: File) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, it) }
        if (!tmp.renameTo(f)) tmp.delete()
    }

    companion object {
        const val PAGE_WIDTH = 1860
        private const val MEMORY_PAGES = 3
        private const val SMALL_SAMPLE = 4
        private const val PROBE = 8
        private const val BLACK_MEAN = 40
        private const val WEBP_QUALITY = 90
        private const val MAX_HEIGHT_FACTOR = 3 // a page at most three page-widths tall in pixels
    }
}
