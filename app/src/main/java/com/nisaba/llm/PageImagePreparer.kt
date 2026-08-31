package com.nisaba.llm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import com.nisaba.ink.StrokeRenderer
import com.nisaba.notebook.model.NotebookPage
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Renders the current page (strokes + PDF background) into a compact
 * grayscale PNG data-URI for the vision model (docs/07 §2, ADR-001).
 *
 * - Long edge capped at [MAX_LONG_EDGE] px (≤ ~1.5 MP, ≤ ~2 MB payload).
 * - White background (best contrast for handwriting); grayscale filter.
 * - UI chrome and previous answers are never included.
 */
object PageImagePreparer {

    const val MAX_LONG_EDGE = 1600

    fun prepare(page: NotebookPage, background: Bitmap?): String {
        val scale = minOf(1f, MAX_LONG_EDGE.toFloat() / maxOf(page.width, page.height))
        val w = (page.width * scale).toInt().coerceAtLeast(1)
        val h = (page.height * scale).toInt().coerceAtLeast(1)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val gray = Paint().apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply { setSaturation(0f) }
            )
            isFilterBitmap = true
        }

        canvas.save()
        canvas.scale(scale, scale)
        if (background != null) {
            canvas.drawBitmap(background, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), gray)
        }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.rgb(0x2A, 0x24, 0x1C)
            colorFilter = gray.colorFilter
        }
        for (stroke in page.strokes) StrokeRenderer.draw(canvas, stroke, ink)
        canvas.restore()

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        bmp.recycle()
        val b64 = Base64.getEncoder().encodeToString(out.toByteArray())
        return "data:image/png;base64,$b64"
    }
}
