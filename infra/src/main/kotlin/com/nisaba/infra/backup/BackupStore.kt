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
            zip.putNextEntry(ZipEntry(MARKER)); zip.write("nisaba backup v1\n".toByteArray()); zip.closeEntry()
            for (root in ROOTS) {
                val dir = File(data, root)
                dir.walkTopDown().filter { it.isFile && keep(it) }.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(data).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Replaces what is on this device with the backup in [input]. False (and nothing touched) when it is not a Nisaba
     * backup. The app must restart right after: the database it had open is gone.
     */
    suspend fun restore(input: InputStream): Boolean = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
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
        for (root in ROOTS) {
            val from = File(staging, root)
            if (!from.exists()) continue
            File(data, root).listFiles()?.filter { keep(it) }?.forEach { it.deleteRecursively() }
            from.copyRecursively(File(data, root), overwrite = true)
        }
        if (!File(staging, "$DB-wal").exists()) File(data, "$DB-wal").delete() // no log came along: none may linger
        File(data, "$DB-shm").delete()
        staging.deleteRecursively()
        true
    }

    /** Not the sealed API key, not caches, not SQLite's shared-memory index (rebuilt on opening; the -wal is kept). */
    private fun keep(f: File) = f.name != "secrets.xml" && !f.path.contains("/cache/") && !f.name.endsWith("-shm")

    private companion object {
        const val MARKER = "nisaba-backup.txt"
        const val DB = "databases/nisaba.db"
        val ROOTS = listOf("databases", "files", "shared_prefs")
    }
}
