package com.nisaba.infra.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.notebook.Paper
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

    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RULE_INK; strokeWidth = 2f }

    fun draw(canvas: Canvas, size: PageSize, background: PageBackground, paper: Paper = Paper.Blank) {
        canvas.drawRect(0f, 0f, size.w, size.h, paint)
        if (background !is PageBackground.DocumentPage) {
            if (!size.isEndless) drawPaper(canvas, size, paper)
            return
        }
        val raster = rasters?.cached(background.documentId, background.index)
        if (raster != null) canvas.drawBitmap(raster, null, RectF(0f, 0f, size.w, size.h), bitmapPaint)
        else canvas.drawRect(FRAME_INSET, FRAME_INSET, size.w - FRAME_INSET, size.h - FRAME_INSET, frame) // still rendering
    }

    /** Lines, a grid or dots [Paper.RULE] apart, faint enough that ink and answers stay the darkest thing on the page. */
    private fun drawPaper(canvas: Canvas, size: PageSize, paper: Paper) {
        val r = Paper.RULE
        when (paper) {
            Paper.Blank -> Unit
            Paper.Lined -> { var y = r * 2; while (y < size.h - r) { canvas.drawLine(r / 2, y, size.w - r / 2, y, rule); y += r } }
            Paper.Grid -> {
                var y = r; while (y < size.h) { canvas.drawLine(0f, y, size.w, y, rule); y += r }
                var x = r; while (x < size.w) { canvas.drawLine(x, 0f, x, size.h, rule); x += r }
            }
            Paper.Dots -> {
                var y = r; while (y < size.h) { var x = r; while (x < size.w) { canvas.drawCircle(x, y, DOT, rule); x += r }; y += r }
            }
        }
    }

    companion object {
        const val PAPER = 0xFFF6F2EA.toInt()
        private const val RULE_INK = 0xFFD9D2C3.toInt()
        private const val DOT = 2.5f
        private const val FRAME = 0xFFC9C3B6.toInt()
        private const val FRAME_INSET = 24f
    }
}
