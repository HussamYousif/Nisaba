package com.nisaba.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import com.nisaba.magic.MagicTheme
import com.nisaba.notebook.model.NotebookPage
import kotlin.math.min
import kotlin.math.pow

/**
 * The ink domain's single public page→bitmap rendering surface
 * (docs/architecture.md §3): strokes (+ optional PDF background) into a new
 * bitmap at the requested size, with optional grayscale.
 *
 * This is a STRICT leaf: it calls nothing else inside the ink module. The
 * pressure-width curve is intentionally parallel to [StrokeRenderer] — the
 * two files serve different layers (live view vs offscreen) and are each
 * deletable in isolation; the instrumented tests pin both.
 *
 * Used by: notebook thumbnails (NotebookStore) and LLM vision-image prep
 * (PageImagePreparer) — those domains never touch StrokeRenderer internals.
 */
object InkPageRenderer {

    /** Pressure→width factor; must stay visually consistent with StrokeRenderer. */
    private fun widthAt(base: Float, p: Float): Float {
        val pp = p.coerceIn(0f, 1f)
        return base * (0.30f + 0.95f * pp.pow(1.4f))
    }

    /**
     * @param width,height   bitmap size in px (aspect need not match the page —
     *                       the page is contain-fit and centered, like the view)
     * @param background     decoded PDF page background, optional
     * @param backgroundColor canvas color behind everything (parchment or white)
     * @param grayscale      desaturate (used for vision prep)
     */
    fun render(
        page: NotebookPage,
        width: Int,
        height: Int,
        background: Bitmap? = null,
        backgroundColor: Int = MagicTheme.PARCHMENT,
        grayscale: Boolean = false,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(backgroundColor)

        val grayFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        val bgPaint = Paint().apply {
            isFilterBitmap = true
            if (grayscale) colorFilter = grayFilter
        }
        val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = MagicTheme.INK
            if (grayscale) colorFilter = grayFilter
        }

        val scale = min(width.toFloat() / page.width, height.toFloat() / page.height)
        canvas.save()
        canvas.translate((width - page.width * scale) / 2f, (height - page.height * scale) / 2f)
        canvas.scale(scale, scale)

        if (background != null) {
            canvas.drawBitmap(
                background, null,
                RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()),
                bgPaint,
            )
        }
        for (stroke in page.strokes) {
            if (stroke.tool == "eraser") continue
            val pts = stroke.points
            if (pts.size < 2) continue
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                inkPaint.strokeWidth = widthAt(stroke.width, (a.p + b.p) / 2f)
                canvas.drawLine(a.x, a.y, b.x, b.y, inkPaint)
            }
        }
        canvas.restore()
        return bmp
    }
}
