package com.nisaba.infra.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.shared.DocumentId
import com.nisaba.domain.shared.PageSize

/** Where a document page's picture comes from. `cached` never blocks: null means "not rendered yet". */
interface RasterSource {
    fun cached(documentId: DocumentId, pageIndex: Int): Bitmap?
}

/** Paper, and for document pages the rendered page under the ink. */
class BackgroundRenderer(private val paperColor: Int = PAPER, private val rasters: RasterSource? = null) {
    private val paint = Paint().apply { color = paperColor }
    private val frame = Paint().apply { color = FRAME; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun draw(canvas: Canvas, size: PageSize, background: PageBackground) {
        canvas.drawRect(0f, 0f, size.w, size.h, paint)
        if (background !is PageBackground.DocumentPage) return
        val raster = rasters?.cached(background.documentId, background.index)
        if (raster != null) canvas.drawBitmap(raster, null, RectF(0f, 0f, size.w, size.h), bitmapPaint)
        else canvas.drawRect(FRAME_INSET, FRAME_INSET, size.w - FRAME_INSET, size.h - FRAME_INSET, frame) // still rendering
    }

    companion object {
        const val PAPER = 0xFFF6F2EA.toInt()
        private const val FRAME = 0xFFC9C3B6.toInt()
        private const val FRAME_INSET = 24f
    }
}
