package com.nisaba.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import com.nisaba.domain.ink.InkStore
import com.nisaba.domain.notebook.Page
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.notebook.Paper
import com.nisaba.infra.document.DocumentRasterCache
import com.nisaba.infra.render.BackgroundRenderer
import com.nisaba.infra.render.InkRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Pages as small pictures for the page grid: paper or printed page, then the ink; kept while the ink is unchanged. */
class ThumbnailRenderer(
    private val ink: InkStore,
    private val background: BackgroundRenderer,
    private val rasters: DocumentRasterCache,
) {
    private val ownInk = InkRenderer() // its own outline cache: a grid of pages would flush the page view's
    private val cache = LruCache<String, Bitmap>(CACHED)
    private val drawing = kotlinx.coroutines.sync.Mutex() // the grid asks for many at once; one renderer, one paint
    private val smooth = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    suspend fun of(page: Page, paper: Paper?): Bitmap? = withContext(Dispatchers.Default) {
        if (page.size.isEndless) return@withContext null
        val strokes = ink.load(page.id).strokes
        val key = "${page.id.value}|${strokes.size}|${strokes.lastOrNull()?.id?.value}|$paper"
        cache[key]?.let { return@withContext it }
        // a printed page small, not the page view's full picture (a fresh PDF's grid would wait on a dozen full renders)
        val printed = (page.background as? PageBackground.DocumentPage)?.let { rasters.small(it.documentId, it.index, WIDTH.toInt()) }
        val scale = WIDTH / page.size.w
        val bmp = Bitmap.createBitmap(WIDTH.toInt(), (page.size.h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        drawing.withLock {
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            if (printed != null) {
                canvas.drawColor(BackgroundRenderer.PAPER)
                canvas.drawBitmap(printed, null, android.graphics.RectF(0f, 0f, page.size.w, page.size.h), smooth)
            } else {
                background.draw(canvas, page.size, page.background, paper ?: Paper.Blank)
            }
            ownInk.drawAll(canvas, strokes)
        }
        cache.put(key, bmp)
        bmp
    }

    private companion object {
        const val WIDTH = 240f
        const val CACHED = 48
    }
}
