package com.nisaba.infra.backup

import android.content.Context
import com.nisaba.infra.db.NisabaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything a person has written, in one zip: the database, the ink and PDF files, the settings and per-page
 * memories. The API key is left out (it is sealed to this device's keystore and would not open elsewhere).
 */
class BackupStore(private val context: Context) {
    private val data get() = context.dataDir

    /** Writes a backup to [out] (closed by the caller). */
    suspend fun backup(out: OutputStream) = withContext(Dispatchers.IO) {
        // fold the write-ahead log into the database file (a pragma only runs when its cursor is stepped); whatever a
        // busy reader kept in the log still goes along in the -wal file
        NisabaDatabase.shared(context).openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        ZipOutputStream(out.buffered()).use { zip ->
            for (root in ROOTS) {
                val dir = File(data, root)
                dir.walkTopDown().filter { it.isFile && keep(it) }.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(data).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            // last: a backup cut short (a file gone mid-walk, a full disk) has no marker, and a restore refuses it
            zip.putNextEntry(ZipEntry(MARKER)); zip.write("nisaba backup v1\n".toByteArray()); zip.closeEntry()
        }
    }

    /**
     * Replaces what is on this device with the backup in [input]. False (and nothing touched) when it is not a Nisaba
     * backup. The app must restart right after: the database it had open is gone.
     */
    suspend fun restore(input: InputStream): Boolean = withContext(Dispatchers.IO) {
        // staged beside what it replaces (same disk, so each folder swaps by a rename; never in a cache the system clears)
        val staging = File(data, "restore-staging").apply { deleteRecursively(); mkdirs() }
        var marked = false
        var hasDb = false
        ZipInputStream(input.buffered()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                if (e.name == MARKER) { marked = true; return@forEach }
                val target = File(staging, e.name)
                if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) return@forEach // no escaping the folder
                if (ROOTS.none { e.name.startsWith("$it/") }) return@forEach
                if (e.name == DB) hasDb = true
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
        }
        if (!marked || !hasDb) { staging.deleteRecursively(); return@withContext false }
        NisabaDatabase.closeShared()
        swapIn(staging)
        staging.deleteRecursively()
        true
    }

    /**
     * Each folder of [staging] replaces its live one by renames, the old kept aside until all are in; any failure puts
     * every old folder back (and throws). The sealed key, which a backup never holds, moves across.
     */
    private fun swapIn(staging: File) {
        val swapped = ArrayList<String>()
        try {
            for (root in ROOTS) {
                val incoming = File(staging, root).apply { mkdirs() }
                val live = File(data, root)
                val old = File(data, "$root.old").apply { deleteRecursively() }
                if (live.exists()) { carryKey(live, incoming); check(live.renameTo(old)) { "could not set $root aside" } }
                swapped += root
                check(incoming.renameTo(live)) { "could not put $root in place" }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            swapped.forEach(::putBack)
            throw e
        }
        for (root in ROOTS) File(data, "$root.old").deleteRecursively()
    }

    /** The sealed key stays with the device: from the live folder into the one coming in. */
    private fun carryKey(live: File, incoming: File) =
        live.walkTopDown().filter { it.isFile && it.name == SECRETS }.forEach { f ->
            f.copyTo(File(incoming, f.relativeTo(live).path), overwrite = true)
        }

    private fun putBack(root: String) {
        val live = File(data, root); val old = File(data, "$root.old")
        if (old.exists()) { live.deleteRecursively(); old.renameTo(live) }
    }

    /** Not the sealed API key, not caches, not SQLite's shared-memory index (rebuilt on opening; the -wal is kept). */
    private fun keep(f: File) = f.name != SECRETS && !f.path.contains("/cache/") && !f.name.endsWith("-shm")

    private companion object {
        const val MARKER = "nisaba-backup.txt"
        const val DB = "databases/nisaba.db"
        val ROOTS = listOf("databases", "files", "shared_prefs")
        const val SECRETS = "secrets.xml"
    }
}
