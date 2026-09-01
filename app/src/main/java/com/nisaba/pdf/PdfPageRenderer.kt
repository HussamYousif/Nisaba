package com.nisaba.pdf

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File
import kotlin.math.roundToInt

/**
 * The pdf domain's single page→bitmap seam (docs/architecture.md §3).
 *
 * Android 15's framework `PdfRenderer` (the new Skia-based PdfProcessor)
 * renders every page as a solid black bitmap on some devices — verified on
 * the Boox Note Air 5c (Android 15) and on API 34/35 emulators. This seam
 * renders with the framework first (fast, correct on healthy devices) and,
 * when the output is detectably black, falls back to the bundled
 * PdfBox-Android engine (pure Java, works everywhere).
 *
 * Leaf: calls only framework/external engines, nothing inside this module.
 */
object PdfPageRenderer {

    /** A page is "black-broken" when > 85% of sampled pixels are dark. */
    private const val BLACK_RATIO = 0.85f

    /**
     * Renders page [index] of [pdfFile] at [dpi] into an RGB_565 bitmap.
     * Returns null when every engine fails.
     */
    @Synchronized // PdfBox documents are not thread-safe across renders
    fun render(file: File, index: Int, dpi: Float): Bitmap? {
        val framework = renderWithFramework(file, index, dpi)
        if (framework != null && !looksBlack(framework)) return framework
        framework?.recycle()
        return renderWithPdfBox(file, index, dpi)
    }

    // ------------------------------------------------------------ engines

    private fun renderWithFramework(file: File, index: Int, dpi: Float): Bitmap? = runCatching {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        try {
            if (index !in 0 until renderer.pageCount) return null
            renderer.openPage(index).use { page ->
                val scale = dpi / 72f
                val w = (page.width * scale).roundToInt()
                val h = (page.height * scale).roundToInt()
                val argb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val matrix = Matrix().apply { setScale(scale, scale) }
                page.render(argb, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                val rgb565 = argb.copy(Bitmap.Config.RGB_565, false)
                argb.recycle()
                rgb565
            }
        } finally {
            renderer.close()
            fd.close()
        }
    }.getOrNull()

    private fun renderWithPdfBox(file: File, index: Int, dpi: Float): Bitmap? = runCatching {
        PDDocument.load(file).use { doc ->
            val renderer = PDFRenderer(doc)
            if (index !in 0 until doc.numberOfPages) return null
            val argb = renderer.renderImageWithDPI(index, dpi.toFloat())
            val rgb565 = argb.copy(Bitmap.Config.RGB_565, false)
            argb.recycle()
            rgb565
        }
    }.getOrNull()

    // ------------------------------------------------------------ check

    private fun looksBlack(bmp: Bitmap): Boolean {
        var dark = 0
        var total = 0
        val step = 16
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val v = bmp.getPixel(x, y)
                val lum = ((v shr 16 and 0xFF) + (v shr 8 and 0xFF) + (v and 0xFF)) / 3
                if (lum < 60) dark++
                total++
                x += step
            }
            y += step
        }
        return total > 0 && dark.toFloat() / total > BLACK_RATIO
    }
}
