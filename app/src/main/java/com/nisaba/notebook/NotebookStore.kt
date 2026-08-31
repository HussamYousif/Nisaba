package com.nisaba.notebook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import com.nisaba.magic.MagicTheme
import com.nisaba.ink.StrokeRenderer
import com.nisaba.notebook.model.Notebook
import com.nisaba.notebook.model.NotebookPage
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

/**
 * Renders a page (background + strokes) into a small shelf thumbnail
 * (docs/05 §2, F1.3). Strokes scale down cleanly since they are vector.
 */
object ThumbnailRenderer {

    const val W = 480
    const val H = 360

    fun render(page: NotebookPage, background: android.graphics.Bitmap? = null): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(MagicTheme.PARCHMENT)
        val s = minOf(W.toFloat() / page.width, H.toFloat() / page.height)
        canvas.save()
        canvas.translate((W - page.width * s) / 2f, (H - page.height * s) / 2f)
        canvas.scale(s, s)
        if (background != null) {
            canvas.drawBitmap(background, null, android.graphics.RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), Paint())
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = MagicTheme.INK
        }
        for (stroke in page.strokes) StrokeRenderer.draw(canvas, stroke, paint)
        canvas.restore()
        return bmp
    }
}

/**
 * Notebook persistence (docs/05 §4): one directory per notebook under
 * filesDir/notebooks/<id>/ with notebook.json (atomic tmp+rename write),
 * thumb.png, and later backgrounds/.
 */
class NotebookStore(private val context: Context) {

    private val root: File = File(context.filesDir, "notebooks")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun dirOf(id: String): File = File(root, id)

    // ------------------------------------------------------------ create

    fun createBlank(): Notebook {
        root.mkdirs()
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val count = list().size
        val nb = Notebook(
            id = id,
            title = "Notebook ${count + 1}",
            createdAt = now,
            updatedAt = now,
        )
        save(nb)
        return nb
    }

    // ------------------------------------------------------------ read

    fun list(): List<NotebookSummary> {
        root.mkdirs()
        return root.listFiles()
            ?.filter { it.isDirectory && File(it, "notebook.json").exists() }
            ?.mapNotNull { dir ->
                try {
                    val nb = parse(File(dir, "notebook.json"))
                    NotebookSummary(nb.id, nb.title, nb.updatedAt, nb.lastOpenedPage)
                } catch (_: Exception) {
                    null // corrupt notebook: hidden from shelf until repaired
                }
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    fun load(id: String): Notebook {
        val file = File(dirOf(id), "notebook.json")
        return parse(file)
    }

    private fun parse(file: File): Notebook =
        json.decodeFromString(Notebook.serializer(), file.readText())

    // ------------------------------------------------------------ write

    /**
     * Atomic write: serialize → tmp file → fsync → rename. A crash mid-write
     * leaves the old notebook.json intact (docs/05 §4).
     */
    fun save(nb: Notebook) {
        nb.updatedAt = Instant.now().toString()
        root.mkdirs()
        val dir = dirOf(nb.id)
        dir.mkdirs()
        val tmp = File(dir, "notebook.json.tmp")
        val final = File(dir, "notebook.json")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(Notebook.serializer(), nb).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(final)) {
            final.delete()
            if (!tmp.renameTo(final)) throw IllegalStateException("rename failed for ${nb.id}")
        }
        writeThumbnail(nb)
    }

    private fun writeThumbnail(nb: Notebook) {
        try {
            val page = nb.pages.getOrNull(nb.lastOpenedPage) ?: nb.pages.firstOrNull() ?: return
            val bg = page.background?.let { rel ->
                runCatching {
                    BitmapFactory.decodeFile(File(dirOf(nb.id), rel).absolutePath)
                }.getOrNull()
            }
            val bmp = ThumbnailRenderer.render(page, bg)
            FileOutputStream(File(dirOf(nb.id), "thumb.png")).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            bmp.recycle()
            bg?.recycle()
        } catch (_: Exception) {
            // Thumbnails are decorative; failure is non-fatal.
        }
    }

    fun thumbnailFile(id: String): File = File(dirOf(id), "thumb.png")

    // ------------------------------------------------------------ delete

    fun delete(id: String) {
        dirOf(id).deleteRecursively()
    }
}

data class NotebookSummary(
    val id: String,
    val title: String,
    val updatedAt: String,
    val lastOpenedPage: Int,
)
