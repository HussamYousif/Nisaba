package com.nisaba.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * LRU cache of decoded page backgrounds (docs/06 §3–5, N6).
 * - At most [maxEntries] decoded bitmaps (~8 MB each, RGB_565).
 * - Backgrounds are a *cache*: if a PNG is missing it is re-rendered from the
 *   notebook's import.pdf on demand — a notebook never becomes unreadable.
 * - Decoding/re-rendering runs on a single background executor; results are
 *   delivered on the main thread.
 */
class PageBackgroundCache(
    private val notebookDir: File,
    private val pdfFile: File?,
    private val maxEntries: Int = 3,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nisaba-bg").apply { priority = Thread.NORM_PRIORITY }
    }

    private val lru = object : LinkedHashMap<Int, Bitmap>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>): Boolean {
            if (size > maxEntries) {
                eldest.value.recycle()
                return true
            }
            return false
        }
    }

    /** Asks for page [index]; [onReady] is invoked on the main thread. */
    fun get(index: Int, onReady: (Bitmap?) -> Unit) {
        synchronized(lru) {
            lru[index]?.let { bmp ->
                mainHandler.post { onReady(bmp) }
                return
            }
        }
        executor.execute {
            val bmp = decode(index)
            if (bmp != null) {
                synchronized(lru) { lru[index] = bmp }
            }
            mainHandler.post { onReady(bmp) }
        }
    }

    /** Suspending variant for summon-time image preparation. */
    suspend fun getSuspending(index: Int): Bitmap? =
        kotlin.coroutines.suspendCoroutine { cont ->
            get(index, onReady = { cont.resume(it) })
        }

    private fun decode(index: Int): Bitmap? {
        // 1. Existing PNG?
        val png = backgroundFile(index)
        if (png.exists()) {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            return BitmapFactory.decodeFile(png.absolutePath, opts)
        }
        // 2. Re-derive from the imported PDF (through the engine seam).
        val pdf = pdfFile ?: return null
        if (!pdf.exists()) return null
        return runCatching {
            val bmp = PdfPageRenderer.render(pdf, index, PdfImporter.RENDER_DPI) ?: return null
            // Persist so the next open is cheap.
            png.parentFile?.mkdirs()
            FileOutputStream(png).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 90, fos)
            }
            bmp
        }.getOrNull()
    }

    fun backgroundFile(index: Int): File =
        File(notebookDir, "backgrounds/${PdfImporter.BACKGROUND_PREFIX}$index.png")

    fun shutdown() {
        executor.shutdown()
    }
}
