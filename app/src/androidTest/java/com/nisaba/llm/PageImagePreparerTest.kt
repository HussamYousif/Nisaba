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

        assertTrue(uri.startsWith("data:image/png;base64,"))
        val bytes = Base64.getDecoder().decode(uri.removePrefix("data:image/png;base64,"))
        assertTrue("payload too big: ${bytes.size}", bytes.size < 2_000_000)

        // Decode PNG header: dimensions must respect the long-edge cap.
        val w = readInt(bytes, 16)
        val h = readInt(bytes, 20)
        assertTrue("w=$w h=$h", maxOf(w, h) <= PageImagePreparer.MAX_LONG_EDGE)
        assertEquals(2480f / 1860f, w.toFloat() / h, 0.02f) // aspect preserved
    }

    @Test
    fun prepare_withPdfBackground_includesIt() {
        val page = NotebookPage(index = 0, width = 1654, height = 2339, background = "backgrounds/p0.png")
        val bg = Bitmap.createBitmap(100, 140, Bitmap.Config.RGB_565) // dummy background
        val uri = PageImagePreparer.prepare(page, background = bg)
        assertTrue(uri.startsWith("data:image/png;base64,"))
        val bytes = Base64.getDecoder().decode(uri.removePrefix("data:image/png;base64,"))
        val w = readInt(bytes, 16)
        val h = readInt(bytes, 20)
        assertEquals(1654f / 2339f, w.toFloat() / h, 0.02f)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
