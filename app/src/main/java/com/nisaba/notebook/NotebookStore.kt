package com.nisaba.notebook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nisaba.magic.MagicTheme
import com.nisaba.notebook.model.Notebook
import com.nisaba.notebook.model.NotebookPage
import com.nisaba.render.PageThumbnail
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

/**
 * Notebook persistence (docs/05 §4): one directory per notebook under
 * filesDir/notebooks/<id>/ with notebook.json (atomic tmp+rename write),
 * thumb.png (rendered by the render domain — see PageThumbnail), and later
 * backgrounds/.
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
            // A tmp-only dir is a crash between fsync and rename: recover it
            // (audit P0-6), or the notebook silently vanishes from the shelf.
            ?.filter { dir ->
                dir.isDirectory &&
                    (File(dir, "notebook.json").exists() || File(dir, "notebook.json.tmp").exists())
            }
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

    private fun parse(file: File): Notebook {
        if (!file.exists()) {
            // Recovery (docs/05:120, audit P0-6): a crash between fsync and
            // rename leaves a complete tmp while notebook.json is missing —
            // read it rather than losing the notebook.
            val tmp = File(file.parentFile, "notebook.json.tmp")
            if (tmp.exists()) {
                return json.decodeFromString(Notebook.serializer(), tmp.readText())
            }
        }
        return json.decodeFromString(Notebook.serializer(), file.readText())
    }

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
            // Never delete the last known-good copy to retry a rename that
            // just failed (audit P0-6) — a second failure would lose the
            // notebook entirely, and list() filters on notebook.json existing.
            throw IllegalStateException("rename failed for ${nb.id}")
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
            val bmp = PageThumbnail.render(page, bg)
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
