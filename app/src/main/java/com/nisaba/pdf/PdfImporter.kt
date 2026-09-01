package com.nisaba.pdf

import android.graphics.Bitmap
import com.nisaba.notebook.NotebookStore
import com.nisaba.notebook.model.Notebook
import com.nisaba.notebook.model.NotebookPage
import com.nisaba.notebook.model.NotebookSource
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

/**
 * "Open a Tome" (docs/06): copies a PDF into a new notebook and renders every
 * page to a background PNG (≤ 200 dpi, RGB_565). Transactional: any failure
 * removes the half-created notebook directory (F6.2, docs/06 §1).
 *
 * Page space is the PDF page box at 300 dpi; background bitmaps are stored at
 * 200 dpi and upscaled at draw time — strokes stay vector-crisp (docs/06 §2).
 */
class PdfImporter(private val store: NotebookStore) {

    class ImportCancelled : Exception("import cancelled by user")

    class Result(val notebook: Notebook, val pageCount: Int)

    /**
     * @param displayName original file name (title source), e.g. "lecture-notes.pdf".
     * @param progress (pageDone, pageTotal) — called on the calling thread.
     * @param cancelled polled per page; throwing [ImportCancelled] aborts.
     */
    fun importFrom(
        stream: InputStream,
        displayName: String? = null,
        progress: (Int, Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): Result {
        val id = UUID.randomUUID().toString()
        val dir = store.dirOf(id)
        dir.mkdirs()
        try {
            // 1. Copy the PDF into the notebook (survives source deletion, F6.5).
            val pdfFile = File(dir, IMPORT_PDF)
            FileOutputStream(pdfFile).use { out -> stream.copyTo(out, BUFFER) }

            // 2. Render each page to a background PNG (one engine session for
            //    the whole document — audit P1-8) and collect page geometry.
            val pages = renderAll(pdfFile, dir, progress, cancelled)
            if (pages.isEmpty()) throw IllegalArgumentException("empty PDF")

            // 3. Build the notebook and persist it.
            val now = Instant.now().toString()
            val nb = Notebook(
                id = id,
                title = titleOf(displayName, pages.size),
                createdAt = now,
                updatedAt = now,
                source = NotebookSource(
                    type = "pdf",
                    file = IMPORT_PDF,
                    pageCount = pages.size,
                    importedAt = now,
                ),
                pages = pages,
            )
            // Synchronous: the caller opens the notebook (loads from disk)
            // the moment importFrom returns (audit P1-3).
            store.saveNow(nb)
            return Result(nb, pages.size)
        } catch (e: ImportCancelled) {
            dir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    // ------------------------------------------------------------------

    private fun renderAll(
        pdfFile: File,
        dir: File,
        progress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
    ): List<NotebookPage> {
        PdfPageRenderer.Session(pdfFile).use { session ->
            val count = session.pageCount
            val bgDir = File(dir, "backgrounds")
            bgDir.mkdirs()
            val s = PAGE_SPACE_DPI / 72f
            val pages = ArrayList<NotebookPage>(count)
            for (i in 0 until count) {
                if (cancelled()) throw ImportCancelled()
                progress(i, count)
                val bmp = session.render(i, RENDER_DPI)
                    ?: throw IllegalStateException("page $i could not be rendered")
                FileOutputStream(File(bgDir, "$BACKGROUND_PREFIX$i.png")).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 90, fos)
                }
                bmp.recycle()
                // The logical page box at 300 dpi (strokes live here) — read
                // from the already-open session, no third open (audit P1-8).
                val (w, h) = session.pageBox(i)
                pages.add(
                    NotebookPage(
                        index = i,
                        width = (w * s).roundToInt(),
                        height = (h * s).roundToInt(),
                        background = "backgrounds/$BACKGROUND_PREFIX$i.png",
                    )
                )
            }
            progress(count, count)
            return pages
        }
    }

    private fun titleOf(displayName: String?, pageCount: Int): String {
        val name = displayName
            ?.substringBeforeLast('.')
            ?.trim()
            .orEmpty()
        return name.ifBlank { "Tome ($pageCount pages)" }
    }

    companion object {
        const val IMPORT_PDF = "import.pdf"
        const val BACKGROUND_PREFIX = "p"
        const val RENDER_DPI = 200f
        const val PAGE_SPACE_DPI = 300f
        private const val BUFFER = 64 * 1024
    }
}
