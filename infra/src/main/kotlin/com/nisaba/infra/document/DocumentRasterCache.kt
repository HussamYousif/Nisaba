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

    private fun key(id: DocumentId, index: Int) = "${id.value}/$index"
    private fun file(id: DocumentId, index: Int) = File(File(rasterDir, id.value), "$index.webp")

    /** Memory only, so it is safe on the UI thread; [ensure] brings a page in from disk or renders it. */
    override fun cached(documentId: DocumentId, pageIndex: Int): Bitmap? = memory[key(documentId, pageIndex)]

    private fun fromDisk(documentId: DocumentId, pageIndex: Int): Bitmap? {
        val f = file(documentId, pageIndex)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path)?.also { memory.put(key(documentId, pageIndex), it) }
    }

    /** Renders (if needed) and returns the picture of a page; null if the file cannot be rendered at all. */
    suspend fun ensure(documentId: DocumentId, pageIndex: Int): Bitmap? = withContext(Dispatchers.IO) {
        cached(documentId, pageIndex) ?: lock.withLock {
            cached(documentId, pageIndex) ?: fromDisk(documentId, pageIndex) ?: render(documentId, pageIndex)?.also { bmp ->
                memory.put(key(documentId, pageIndex), bmp)
                runCatching { save(bmp, file(documentId, pageIndex)) }
            }
        }
    }

    private fun render(id: DocumentId, index: Int): Bitmap? {
        val pdf = File(docsDir, "${id.value}.pdf")
        if (!pdf.exists()) return null
        val system = runCatching { renderWithSystem(pdf, index) }.getOrNull()
        return if (system != null && !looksBlack(system)) system else runCatching { renderWithPdfBox(pdf, index) }.getOrNull() ?: system
    }

    private fun renderWithSystem(pdf: File, index: Int): Bitmap =
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(index).use { page ->
                    val h = (widthPx.toLong() * page.height / page.width).toInt()
                    val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }

    private fun renderWithPdfBox(pdf: File, index: Int): Bitmap = PDDocument.load(pdf).use { doc ->
        val box = doc.getPage(index).mediaBox
        PDFRenderer(doc).renderImage(index, widthPx / box.width)
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

    private fun save(bmp: Bitmap, f: File) {
        f.parentFile?.mkdirs()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, it) }
    }

    companion object {
        const val PAGE_WIDTH = 1860
        private const val MEMORY_PAGES = 3
        private const val PROBE = 8
        private const val BLACK_MEAN = 40
        private const val WEBP_QUALITY = 90
    }
}
