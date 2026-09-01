package com.nisaba.pdf

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.Closeable
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
     * One import/render session: a framework [PdfRenderer] plus a lazily
     * opened PdfBox [PDDocument], both held open across the whole run.
     * Re-opening and re-parsing the document once per page made imports
     * O(n²) on the Boox (audit P1-8).
     */
    class Session(pdfFile: File) : Closeable {
        private val file = pdfFile
        private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        private val framework = PdfRenderer(fd)
        private var pdfBox: PDDocument? = null

        private fun pdfBoxDoc(): PDDocument =
            pdfBox ?: PDDocument.load(file).also { pdfBox = it }

        /**
         * PdfBox's count is authoritative. The Android 15 framework renderer
         * on the Boox does not just render black — it also mis-reports
         * `pageCount` for some PDFs (verified on-device: a 5-page document
         * imported as 1), silently dropping pages. PdfBox is the engine that
         * works on the target device; the framework keeps the fast render
         * path on healthy devices only.
         */
        val pageCount: Int get() = pdfBoxDoc().numberOfPages

        /** Logical page box in points (72 dpi space) — dimensions only. */
        fun pageBox(index: Int): Pair<Int, Int> {
            require(index in 0 until pageCount) { "page $index out of range" }
            val box = pdfBoxDoc().getPage(index).cropBox ?: pdfBoxDoc().getPage(index).mediaBox
            return box.width.roundToInt() to box.height.roundToInt()
        }

        /** Renders page [index] at [dpi] into an RGB_565 bitmap; null when every engine fails. */
        fun render(index: Int, dpi: Float): Bitmap? {
            require(index in 0 until pageCount) { "page $index out of range" }
            val frameworkBmp = renderWithFramework(index, dpi)
            if (frameworkBmp != null && !looksBlack(frameworkBmp)) return frameworkBmp
            frameworkBmp?.recycle()
            return renderWithPdfBox(index, dpi)
        }

        override fun close() {
            pdfBox?.close()
            pdfBox = null
            framework.close()
            fd.close()
        }

        private fun renderWithFramework(index: Int, dpi: Float): Bitmap? = runCatching {
            framework.openPage(index).use { page ->
                val scale = dpi / 72f
                val w = (page.width * scale).roundToInt()
                val h = (page.height * scale).roundToInt()
                val argb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val matrix = Matrix().apply { setScale(scale, scale) }
                page.render(argb, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                val rgb565 = argb.copy(Bitmap.Config.RGB_565, false)
                argb.recycle() // the ARGB render is only a staging buffer
                rgb565
            }
        }.getOrNull()

        private fun renderWithPdfBox(index: Int, dpi: Float): Bitmap? = runCatching {
            // Parse the document once per session, not once per page (P1-8).
            val doc = pdfBoxDoc()
            val renderer = PDFRenderer(doc)
            val argb = renderer.renderImageWithDPI(index, dpi.toFloat())
            val rgb565 = argb.copy(Bitmap.Config.RGB_565, false)
            argb.recycle()
            rgb565
        }.getOrNull()
    }

    /**
     * One-shot convenience for the background cache (docs/06 §4).
     * Opens and closes a session per call; @Synchronized because the shared
     * PdfBox engine state is not thread-safe across concurrent renders.
     */
    @Synchronized
    fun render(file: File, index: Int, dpi: Float): Bitmap? =
        Session(file).use { it.render(index, dpi) }

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
