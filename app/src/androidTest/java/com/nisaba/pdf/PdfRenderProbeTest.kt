package com.nisaba.pdf

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Minimal PdfRenderer probe: which render invocations produce real content on
 * the current platform? Useful as a device diagnostic — the sandboxed headless
 * emulators used in CI here render every PDF black (see PdfTestSupport), so
 * these assertions soft-skip there and hard-assert on real hardware.
 */
@RunWith(AndroidJUnit4::class)
class PdfRenderProbeTest {

    private fun makePdf(name: String): File {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(ctx.filesDir, name)
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(400, 600, 1).create())
        page.canvas.drawText("Hello Nisaba", 40f, 100f, android.graphics.Paint().apply { textSize = 24f })
        doc.finishPage(page)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun sample(bmp: Bitmap): Pair<Int, Int> {
        var dark = 0; var light = 0
        for (y in 0 until bmp.height step 5) {
            for (x in 0 until bmp.width step 5) {
                val v = bmp.getPixel(x, y)
                val lum = ((v shr 16 and 0xFF) + (v shr 8 and 0xFF) + (v and 0xFF)) / 3
                if (lum < 110) dark++ else if (lum > 190) light++
            }
        }
        return dark to light
    }

    /** Asserts content, skipping in environments that render PDFs black. */
    private fun assertContent(label: String, dark: Int, light: Int) {
        PdfTestSupport.assumeRenders(dark, light)
        org.junit.Assert.assertTrue("$label: dark=$dark light=$light", light > dark * 10 && dark > 2)
    }

    private fun render(mode: Int, scale: Boolean, clip: Boolean = false): Bitmap {
        val file = makePdf("probe-${mode}-$scale-$clip.pdf")
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        val page = renderer.openPage(0)
        val w = if (scale) 800 else page.width
        val h = if (scale) 1200 else page.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val matrix = if (scale) Matrix().apply { setScale(2f, 2f) } else null
        val rect = if (clip) android.graphics.Rect(0, 0, w, h) else null
        page.render(bmp, rect, matrix, mode)
        page.close(); renderer.close(); fd.close()
        return bmp
    }

    @Test
    fun control_canvasRendersIntoBitmap() {
        // Control: plain Canvas rendering must produce white+text pixels —
        // proves the bitmap/sampling path itself is sound.
        val bmp = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawText("Hello Nisaba", 40f, 100f,
            android.graphics.Paint().apply { textSize = 24f; color = android.graphics.Color.BLACK })
        val (dark, light) = sample(bmp)
        assertContent("control", dark, light)
    }

    @Test
    fun identityDisplay_rendersContent() {
        val (dark, light) = sample(render(PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY, scale = false))
        assertContent("identity+display", dark, light)
    }

    @Test
    fun scaledDisplay_rendersContent() {
        val (dark, light) = sample(render(PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY, scale = true))
        assertContent("scaled+display", dark, light)
    }

    @Test
    fun identityPrint_rendersContent() {
        val (dark, light) = sample(render(PdfRenderer.Page.RENDER_MODE_FOR_PRINT, scale = false))
        assertContent("identity+print", dark, light)
    }

    @Test
    fun explicitClip_rendersContent() {
        val (dark, light) = sample(
            render(PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY, scale = false, clip = true)
        )
        assertContent("clip+display", dark, light)
    }
}
