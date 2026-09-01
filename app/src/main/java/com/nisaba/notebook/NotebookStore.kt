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
import java.util.concurrent.Executors

/**
 * Notebook persistence (docs/05 §4): one directory per notebook under
 * filesDir/notebooks/<id>/ with notebook.json (atomic tmp+rename write),
 * thumb.png (rendered by the render domain — see PageThumbnail), and later
 * backgrounds/.
 *
 * Writes never touch the caller thread's clock: [save] serializes on the
 * calling thread (the model is mutated from main, so serialization must stay
 * there) and hands the file write + rename to a single-thread executor —
 * the "serialized through a single mutex" docs/03:100 promises. [saveNow]
 * writes synchronously for the load-immediately-after paths (createBlank,
 * PDF import). Thumbnails render only on explicit saves (audit P1-3).
 */
class NotebookStore(private val context: Context) {

    private val root: File = File(context.filesDir, "notebooks")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** FIFO write queue: keeps tmp→rename ordering across overlapping saves. */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nisaba-store").apply { isDaemon = true }
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
        // Synchronous: openNotebook(createBlank().id) loads from disk right away.
        saveNow(nb)
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
                    // Decode only the summary fields, never the full notebook:
                    // a written-on 300-page tome holds hundreds of thousands of
                    // stroke points and deserializing them all to read four
                    // fields made app-open latency scale with total ink ever
                    // written (audit P1-4). ignoreUnknownKeys skips pages.
                    val head = headFrom(File(dir, "notebook.json"))
                    NotebookSummary(head.id, head.title, head.updatedAt, head.lastOpenedPage)
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

    private fun headFrom(file: File): NotebookHead {
        if (!file.exists()) {
            val tmp = File(file.parentFile, "notebook.json.tmp")
            if (tmp.exists()) {
                return json.decodeFromString(NotebookHead.serializer(), tmp.readText())
            }
        }
        return json.decodeFromString(NotebookHead.serializer(), file.readText())
    }

    // ------------------------------------------------------------ write

    /**
     * Atomic write: serialize → tmp file → fsync → rename. A crash mid-write
     * leaves the old notebook.json intact (docs/05 §4). The file write runs
     * on the store's write executor — the caller must not block on it.
     *
     * @param thumbnail render thumb.png too (only for explicit saves — the
     *   800 ms debounce must not decode/encode a bitmap per pause, P1-3).
     */
    fun save(nb: Notebook, thumbnail: Boolean = false) {
        nb.updatedAt = Instant.now().toString()
        val body = serialize(nb)
        writer.execute {
            write(body, nb.id)
            if (thumbnail) writeThumbnail(nb)
        }
    }

    /**
     * Synchronous save for paths that load the file right after (createBlank,
     * PDF import) — an async write would race the follow-up load (P1-3).
     */
    fun saveNow(nb: Notebook, thumbnail: Boolean = true) {
        nb.updatedAt = Instant.now().toString()
        write(serialize(nb), nb.id)
        if (thumbnail) writeThumbnail(nb)
    }

    private fun serialize(nb: Notebook): String =
        json.encodeToString(Notebook.serializer(), nb)

    private fun write(body: String, id: String) {
        root.mkdirs()
        val dir = dirOf(id)
        dir.mkdirs()
        val tmp = File(dir, "notebook.json.tmp")
        val final = File(dir, "notebook.json")
        FileOutputStream(tmp).use { out ->
            out.write(body.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(final)) {
            // Never delete the last known-good copy to retry a rename that
            // just failed (audit P0-6) — a second failure would lose the
            // notebook entirely, and list() filters on notebook.json existing.
            throw IllegalStateException("rename failed for $id")
        }
    }

    private fun writeThumbnail(nb: Notebook) {
        try {
            val page = nb.pages.getOrNull(nb.lastOpenedPage) ?: nb.pages.firstOrNull() ?: return
            val bg = page.background?.let { rel ->
                runCatching {
                    // Decode downsized for PageThumbnail's ~480×360 target —
                    // a full-resolution ARGB_8888 decode per save OOMs on
                    // low-RAM devices (audit P1-3).
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(File(dirOf(nb.id), rel).absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= THUMB_W ||
                        bounds.outHeight / (sample * 2) >= THUMB_H
                    ) {
                        sample *= 2
                    }
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeFile(File(dirOf(nb.id), rel).absolutePath, opts)
                }.getOrNull()
            }
            val bmp = PageThumbnail.render(page, bg)
            FileOutputStream(File(dirOf(nb.id), "thumb.png")).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            bmp.recycle()
            bg?.recycle()
        } catch (_: Throwable) {
            // Thumbnails are decorative; failure is non-fatal. Throwable, not
            // Exception: an OOM must degrade here, not crash the save (P1-3).
        }
    }

    fun thumbnailFile(id: String): File = File(dirOf(id), "thumb.png")

    // ------------------------------------------------------------ delete

    fun delete(id: String) {
        dirOf(id).deleteRecursively()
    }

    companion object {
        private const val THUMB_W = 480
        private const val THUMB_H = 360
    }
}

data class NotebookSummary(
    val id: String,
    val title: String,
    val updatedAt: String,
    val lastOpenedPage: Int,
)

/** The shelf's view of a notebook — summary fields only (audit P1-4). */
@kotlinx.serialization.Serializable
private data class NotebookHead(
    val id: String,
    val title: String,
    val updatedAt: String,
    val lastOpenedPage: Int = 0,
)
