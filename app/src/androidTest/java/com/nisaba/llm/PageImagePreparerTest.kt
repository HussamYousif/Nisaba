package com.nisaba.llm

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.notebook.model.NotebookPage
import com.nisaba.notebook.model.Stroke
import com.nisaba.notebook.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * Page → vision image (docs/07 §2): data-URI shape, size cap, decodability.
 */
@RunWith(AndroidJUnit4::class)
class PageImagePreparerTest {

    @Test
    fun prepare_producesCompactDataUri() {
        val page = NotebookPage(
            index = 0, width = 2480, height = 1860,
            strokes = listOf(
                Stroke(
                    id = "s1",
                    points = listOf(
                        StrokePoint(100f, 200f, 0.5f, 0L),
                        StrokePoint(600f, 700f, 0.9f, 100L),
                        StrokePoint(1200f, 300f, 0.3f, 200L),
                    ),
                )
            ),
        )
        val uri = PageImagePreparer.prepare(page, background = null)

        assertTrue(uri.startsWith("data:image/jpeg;base64,"))
        val bytes = Base64.getDecoder().decode(uri.removePrefix("data:image/jpeg;base64,"))
        assertTrue("payload too big: ${bytes.size}", bytes.size < 2_000_000)

        // JPEG SOF header: dimensions must respect the long-edge cap.
        val (w, h) = jpegDimensions(bytes)
        assertTrue("w=$w h=$h", maxOf(w, h) <= PageImagePreparer.MAX_LONG_EDGE)
        assertEquals(2480f / 1860f, w.toFloat() / h, 0.02f) // aspect preserved
    }

    @Test
    fun prepare_withPdfBackground_includesIt() {
        val page = NotebookPage(index = 0, width = 1654, height = 2339, background = "backgrounds/p0.png")
        val bg = Bitmap.createBitmap(100, 140, Bitmap.Config.RGB_565) // dummy background
        val uri = PageImagePreparer.prepare(page, background = bg)
        assertTrue(uri.startsWith("data:image/jpeg;base64,"))
        val bytes = Base64.getDecoder().decode(uri.removePrefix("data:image/jpeg;base64,"))
        val (w, h) = jpegDimensions(bytes)
        assertEquals(1654f / 2339f, w.toFloat() / h, 0.02f)
    }

    /** Parses the JPEG SOF marker: (width, height). */
    private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int> {
        var i = 2 // skip SOI (FFD8)
        while (i + 9 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) {
                i++
                continue
            }
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                // SOF: height at i+5, width at i+7 (big-endian)
                val h = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                val w = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                return w to h
            }
            val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            i += 2 + len
        }
        return 0 to 0
    }
}
