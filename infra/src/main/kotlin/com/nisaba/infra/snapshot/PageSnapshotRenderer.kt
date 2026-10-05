package com.nisaba.infra.snapshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.nisaba.domain.conversation.PageSnapshot
import com.nisaba.domain.conversation.PageSnapshotter
import com.nisaba.domain.ink.InkStore
import com.nisaba.domain.notebook.NotebookStore
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.PageSize
import com.nisaba.infra.render.BackgroundRenderer
import com.nisaba.infra.render.InkRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a page (background + all ink, including earlier answers) off-screen for the vision model:
 * grayscale JPEG, at most [LONG_EDGE] px on the long side, plus an optional crop around the new writing.
 */
class PageSnapshotRenderer(
    private val notebookId: NotebookId,
    private val notebooks: NotebookStore,
    private val ink: InkStore,
    private val inkRenderer: InkRenderer = InkRenderer(),
    private val backgroundRenderer: BackgroundRenderer = BackgroundRenderer(),
    private val rasters: com.nisaba.infra.document.DocumentRasterCache? = null,
) : PageSnapshotter {

    override suspend fun snapshot(pageId: PageId, focus: PageRect?): PageSnapshot = withContext(Dispatchers.Default) {
        val page = notebooks.get(notebookId)?.pages?.firstOrNull { it.id == pageId }
        val size = page?.size ?: PageSize.PORTRAIT
        (page?.background as? com.nisaba.domain.notebook.PageBackground.DocumentPage)?.let { rasters?.ensure(it.documentId, it.index) }
        val strokes = ink.load(pageId).strokes
        val whole = if (size.isEndless) endlessWindow(focus ?: strokes.lastOrNull()?.bounds) else PageRect(0f, 0f, size.w, size.h)
        val pageJpeg = render(whole, size, page?.background, strokes, LONG_EDGE, 1f)
        val focusJpeg = focus?.let { f ->
            val clipped = PageRect(max(0f, f.left), max(0f, f.top), min(size.w, f.right), min(size.h, f.bottom))
            render(clipped, size, page?.background, strokes, LONG_EDGE, MIN_FOCUS_EDGE.toFloat())
        }
        PageSnapshot(pageJpeg, focusJpeg)
    }

    /** An endless canvas has no "whole page": show the neighbourhood of the new writing, with room above for context. */
    private fun endlessWindow(anchor: PageRect?): PageRect {
        val o = PageSize.ENDLESS_ORIGIN
        val a = anchor ?: PageRect(o, o, o + 1860f, o + 400f)
        val left = a.left - WINDOW_SIDE
        return PageRect(left, a.top - WINDOW_ABOVE, max(a.right + WINDOW_SIDE, left + WINDOW_MIN_WIDTH), a.bottom + WINDOW_BELOW)
    }

    private fun render(
        area: PageRect,
        size: PageSize,
        background: com.nisaba.domain.notebook.PageBackground?,
        strokes: List<com.nisaba.domain.ink.Stroke>,
        maxEdge: Int,
        minEdge: Float,
    ): ByteArray {
        val w = max(1f, area.width); val h = max(1f, area.height)
        var scale = min(1f, maxEdge / max(w, h))
        if (min(w, h) * scale < minEdge) scale = min(maxEdge / max(w, h), minEdge / min(w, h)) // tiny crops are upscaled for legibility
        val bw = max(1, (w * scale).toInt()); val bh = max(1, (h * scale).toInt())
        val color = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(color)
        canvas.scale(scale, scale); canvas.translate(-area.left, -area.top)
        backgroundRenderer.draw(canvas, size, background ?: com.nisaba.domain.notebook.PageBackground.Blank)
        inkRenderer.drawAll(canvas, strokes.filter { s -> s.bounds.let { // an endless canvas holds far more than the window
            it.right >= area.left - PAD && it.left <= area.right + PAD && it.bottom >= area.top - PAD && it.top <= area.bottom + PAD
        } })
        val gray = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        Canvas(gray).drawBitmap(color, 0f, 0f, paint)
        color.recycle()
        val out = ByteArrayOutputStream()
        gray.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        gray.recycle()
        return out.toByteArray()
    }

    private companion object {
        const val LONG_EDGE = 1600
        const val PAD = 16f // widest ink, so strokes just outside the area still show their edge
        const val WINDOW_SIDE = 400f
        const val WINDOW_ABOVE = 1400f
        const val WINDOW_BELOW = 500f
        const val WINDOW_MIN_WIDTH = 2000f
        const val MIN_FOCUS_EDGE = 512
        const val JPEG_QUALITY = 85
    }
}
