package com.nisaba.infra.export

import android.graphics.pdf.PdfDocument
import com.nisaba.domain.conversation.ExchangeStatus
import com.nisaba.domain.conversation.ExchangeStore
import com.nisaba.domain.ink.InkStore
import com.nisaba.domain.notebook.NotebookStore
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.search.plainText
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.union
import com.nisaba.infra.document.DocumentRasterCache
import com.nisaba.infra.render.BackgroundRenderer
import com.nisaba.infra.render.InkRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** A notebook out of the app: every page as a PDF page (paper, printed page, ink), or its conversation as Markdown. */
class ExportRenderer(
    private val notebooks: NotebookStore,
    private val ink: InkStore,
    private val exchanges: ExchangeStore,
    private val background: BackgroundRenderer,
    private val rasters: DocumentRasterCache,
) {
    /**
     * False when the notebook is gone. An endless canvas becomes one page around its ink. A PDF notebook gives the pages
     * written on, each printed page at half resolution: Android keeps every page's pictures until the end, and a whole
     * book at full size would not fit in memory.
     */
    suspend fun pdf(id: NotebookId, out: OutputStream): Boolean = withContext(Dispatchers.Default) {
        val nb = notebooks.get(id) ?: return@withContext false
        val pen = InkRenderer()
        val doc = PdfDocument()
        val printed = nb.pages.any { it.background is PageBackground.DocumentPage }
        try {
            for ((i, page) in nb.pages.withIndex()) {
                val strokes = ink.load(page.id).strokes
                if (printed && strokes.isEmpty()) continue
                sheet(doc, i, page, strokes, nb.notebook.paper, pen)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
        true
    }

    /** One page of the PDF: its paper or printed page, then its ink (an endless canvas: just around the ink). */
    private suspend fun sheet(
        doc: PdfDocument,
        i: Int,
        page: com.nisaba.domain.notebook.Page,
        strokes: List<com.nisaba.domain.ink.Stroke>,
        paper: com.nisaba.domain.notebook.Paper,
        pen: InkRenderer,
    ) {
        (page.background as? PageBackground.DocumentPage)?.let { rasters.ensure(it.documentId, it.index) }
        val area = if (page.size.isEndless) {
            strokes.map { it.bounds }.reduceOrNull { a, b -> a.union(b) } ?: return
        } else {
            com.nisaba.domain.shared.PageRect(0f, 0f, page.size.w, page.size.h)
        }
        val w = area.right - area.left + if (page.size.isEndless) 2 * MARGIN else 0f
        val h = area.bottom - area.top + if (page.size.isEndless) 2 * MARGIN else 0f
        val scale = POINTS_WIDE / w
        val sheet = doc.startPage(PdfDocument.PageInfo.Builder(POINTS_WIDE.toInt(), (h * scale).toInt(), i + 1).create())
        val canvas = sheet.canvas
        canvas.scale(scale, scale)
        when {
            page.size.isEndless -> { canvas.translate(MARGIN - area.left, MARGIN - area.top); canvas.drawColor(BackgroundRenderer.PAPER) }
            page.background is PageBackground.DocumentPage ->
                canvas.drawBitmap(halfSize(page, paper), null, android.graphics.RectF(0f, 0f, page.size.w, page.size.h), SMOOTH)
            else -> background.draw(canvas, page.size, page.background, paper)
        }
        pen.drawAll(canvas, strokes)
        doc.finishPage(sheet)
    }

    /** The printed page at half size (RGB_565, about 2.4 MB): what stays in memory until the PDF is written. */
    private fun halfSize(page: com.nisaba.domain.notebook.Page, paper: com.nisaba.domain.notebook.Paper): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(
            (page.size.w * HALF).toInt().coerceAtLeast(1), (page.size.h * HALF).toInt().coerceAtLeast(1),
            android.graphics.Bitmap.Config.RGB_565,
        )
        android.graphics.Canvas(bmp).apply { scale(HALF, HALF); background.draw(this, page.size, page.background, paper) }
        return bmp
    }

    /** The questions and answers, page by page, as Markdown (failed, cut-off and erased ones left out). */
    suspend fun markdown(id: NotebookId, out: OutputStream): Boolean = withContext(Dispatchers.IO) {
        val nb = notebooks.get(id) ?: return@withContext false
        val kept = setOf(ExchangeStatus.Complete, ExchangeStatus.Interrupted, ExchangeStatus.Silent)
        val byPage = exchanges.listByNotebook(id).filter { it.status in kept }.groupBy { it.pageId }
        val text = buildString {
            append("# ").append(nb.notebook.name).append("\n")
            for ((i, page) in nb.pages.withIndex()) {
                val here = byPage[page.id].orEmpty().sortedBy { it.createdAtMillis }
                if (here.isEmpty()) continue
                append("\n## Page ").append(i + 1).append("\n")
                for (e in here) {
                    if (e.heard.isNotBlank()) append("\n**You:** ").append(e.heard.trim()).append("\n")
                    if (e.status != ExchangeStatus.Silent) append("\n").append(plainText(e.answerMarkup)).append("\n")
                }
            }
        }
        out.write(text.toByteArray())
        true
    }

    private companion object {
        const val POINTS_WIDE = 595f // A4 width in PDF points
        const val MARGIN = 80f
        const val HALF = 0.5f
        val SMOOTH = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    }
}
