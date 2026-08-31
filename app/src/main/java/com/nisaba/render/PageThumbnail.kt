package com.nisaba.render

import android.graphics.Bitmap
import com.nisaba.ink.InkPageRenderer
import com.nisaba.magic.MagicTheme
import com.nisaba.notebook.model.NotebookPage

/**
 * Shelf thumbnail for a page (docs/05 §2, F1.3). Presentation-side concern —
 * lives in the render domain so notebook/data never does rendering
 * (docs/architecture-review.md D-B). Delegates to the ink domain's public
 * rendering surface.
 */
object PageThumbnail {

    const val W = 480
    const val H = 360

    fun render(page: NotebookPage, background: Bitmap? = null): Bitmap =
        InkPageRenderer.render(
            page = page,
            width = W,
            height = H,
            background = background,
            backgroundColor = MagicTheme.PARCHMENT,
        )
}
