package com.nisaba.infra.snapshot

import android.graphics.Bitmap
import android.graphics.Canvas
import com.nisaba.domain.conversation.PageSnapshot
import com.nisaba.domain.conversation.PageSnapshotter
import com.nisaba.domain.conversation.SnapshotLabel
import com.nisaba.domain.ink.InkColor
import com.nisaba.domain.ink.InkStore
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.ink.StrokeOrigin
import com.nisaba.domain.notebook.NotebookStore
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.PageSize
import com.nisaba.domain.shared.StrokeId
import com.nisaba.infra.render.BackgroundRenderer
import com.nisaba.infra.render.InkRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a page (background + all ink, including earlier answers) off-screen for the vision model: a colour JPEG at
 * most [LONG_EDGE] px on the long side, plus an optional crop around the new writing. The new writing is drawn as
 * written, older ink faded, earlier answers always in lapis (whatever ink the person reads them in).
 */
class PageSnapshotRenderer(
    private val notebookId: NotebookId,
    private val notebooks: NotebookStore,
    private val ink: InkStore,
    private val inkRenderer: InkRenderer = InkRenderer(),
    private val backgroundRenderer: BackgroundRenderer = BackgroundRenderer(),
    private val rasters: com.nisaba.infra.document.DocumentRasterCache? = null,
) : PageSnapshotter {

    override suspend fun snapshot(
        pageId: PageId,
        focus: PageRect?,
        fresh: Set<StrokeId>,
        labels: List<SnapshotLabel>,
    ): PageSnapshot = withContext(Dispatchers.Default) {
        val page = notebooks.get(notebookId)?.pages?.firstOrNull { it.id == pageId }
        val size = page?.size ?: PageSize.PORTRAIT
        (page?.background as? com.nisaba.domain.notebook.PageBackground.DocumentPage)?.let { rasters?.ensure(it.documentId, it.index) }
        val strokes = ink.load(pageId).strokes
        val whole = if (size.isEndless) endlessWindow(focus ?: strokes.lastOrNull()?.bounds) else PageRect(0f, 0f, size.w, size.h)
        val argbOf = inkFor(fresh)
        val pageJpeg = render(whole, size, page?.background, strokes, LONG_EDGE, 1f, argbOf, labels)
        val focusJpeg = focus?.let { f ->
            val clipped = PageRect(max(0f, f.left), max(0f, f.top), min(size.w, f.right), min(size.h, f.bottom))
            render(clipped, size, page?.background, strokes, LONG_EDGE, MIN_FOCUS_EDGE.toFloat(), argbOf, labels)
        }
        PageSnapshot(pageJpeg, focusJpeg)
    }

    /** New writing as written; older ink faded toward the paper; answers in lapis (faded too when there is new writing). */
    private fun inkFor(fresh: Set<StrokeId>): (Stroke) -> Int = { s ->
        val base = if (s.origin is StrokeOrigin.Oracle) InkColor.LAPIS.argb else s.color.argb
        if (fresh.isEmpty() || s.id in fresh) base else (base and 0x00FFFFFF) or (FADED_ALPHA shl 24)
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
        argbOf: (Stroke) -> Int,
        labels: List<SnapshotLabel> = emptyList(),
    ): ByteArray {
        val w = max(1f, area.width); val h = max(1f, area.height)
        var scale = min(1f, maxEdge / max(w, h))
        if (min(w, h) * scale < minEdge) scale = min(maxEdge / max(w, h), minEdge / min(w, h)) // tiny crops are upscaled for legibility
        val bw = max(1, (w * scale).toInt()); val bh = max(1, (h * scale).toInt())
        val color = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(color)
        canvas.scale(scale, scale); canvas.translate(-area.left, -area.top)
        backgroundRenderer.draw(canvas, size, background ?: com.nisaba.domain.notebook.PageBackground.Blank)
        val inView = strokes.filter { s -> s.bounds.let { // an endless canvas holds far more than the window
            it.right >= area.left - PAD && it.left <= area.right + PAD && it.bottom >= area.top - PAD && it.top <= area.bottom + PAD
        } }
        inkRenderer.drawAll(canvas, inView, argbOf)
        for (l in labels) { // line numbers for checking work: left of the line, kept inside the picture
            val x = max(area.left + LABEL_GAP, l.at.x - labelPaint.measureText(l.text) - LABEL_GAP)
            canvas.drawText(l.text, x, l.at.y + labelPaint.textSize / 3, labelPaint)
        }
        val out = ByteArrayOutputStream()
        color.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) // in colour: blue answers vs the person's ink
        color.recycle()
        return out.toByteArray()
    }

    private val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = LABEL_RED; textSize = LABEL_SIZE; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private companion object {
        const val LABEL_RED = 0xFFD0201A.toInt()
        const val LABEL_SIZE = 30f
        const val LABEL_GAP = 12f
        const val LONG_EDGE = 1600
        const val PAD = 16f // widest ink, so strokes just outside the area still show their edge
        const val WINDOW_SIDE = 400f
        const val WINDOW_ABOVE = 1400f
        const val WINDOW_BELOW = 500f
        const val WINDOW_MIN_WIDTH = 2000f
        const val MIN_FOCUS_EDGE = 512
        const val JPEG_QUALITY = 85
        const val FADED_ALPHA = 0x70 // older ink at ~44%: still readable as context, clearly not the new writing
    }
}
