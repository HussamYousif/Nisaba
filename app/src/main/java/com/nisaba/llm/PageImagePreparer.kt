package com.nisaba.llm

import android.graphics.Bitmap
import com.nisaba.ink.InkPageRenderer
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
 * - Rendering delegates to the ink domain's public surface
 *   (docs/architecture.md §3, D3).
 */
object PageImagePreparer {

    const val MAX_LONG_EDGE = 2000
    const val STROKE_WIDTH_MULTIPLIER = 1.6f

    fun prepare(page: NotebookPage, background: Bitmap?): String {
        val scale = minOf(1f, MAX_LONG_EDGE.toFloat() / maxOf(page.width, page.height))
        val w = (page.width * scale).toInt().coerceAtLeast(1)
        val h = (page.height * scale).toInt().coerceAtLeast(1)

        val bmp = InkPageRenderer.render(
            page = page,
            width = w,
            height = h,
            background = background,
            backgroundColor = android.graphics.Color.WHITE,
            grayscale = true,
            strokeWidthMultiplier = STROKE_WIDTH_MULTIPLIER,
        )

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        bmp.recycle()
        val b64 = Base64.getEncoder().encodeToString(out.toByteArray())
        return "data:image/png;base64,$b64"
    }
}
