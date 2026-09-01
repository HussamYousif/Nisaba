package com.nisaba.llm

import android.graphics.Bitmap
import com.nisaba.ink.InkPageRenderer
import com.nisaba.notebook.model.NotebookPage
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Renders the current page (strokes + PDF background) into a compact
 * JPEG data-URI for the vision model (docs/07 §2, ADR-001).
 *
 * - Long edge capped at [MAX_LONG_EDGE] px — 1600×1200 = 1.9 MP, a few
 *   hundred KB payload (docs/07 specifies 1600 px; audit P1-7).
 * - White background (best contrast for handwriting); grayscale filter.
 * - UI chrome and previous answers are never included.
 * - Rendering delegates to the ink domain's public surface
 *   (docs/architecture.md §3, D3).
 */
object PageImagePreparer {

    const val MAX_LONG_EDGE = 1600
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
        // JPEG, not PNG: quality actually does something, and handwriting on
        // parchment compresses an order of magnitude better (audit P1-7).
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        val b64 = Base64.getEncoder().encodeToString(out.toByteArray())
        return "data:image/jpeg;base64,$b64"
    }
}
