package com.nisaba.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.nisaba.domain.settings.SettingsStore
import com.nisaba.domain.shared.NotebookId
import com.nisaba.infra.backup.BackupStore
import com.nisaba.infra.export.ExportRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Files that leave the app or come back: exports, backups (now, or weekly to a chosen folder) and restores. */
class FileRegistry(private val app: Context, private val settings: SettingsStore, private val exports: ExportRenderer) {
    private val backups = BackupStore(app)

    /** Writes notebook [id] to [uri] as a PDF ([asPdf]) or as Markdown text. */
    suspend fun export(id: NotebookId, asPdf: Boolean, uri: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            app.contentResolver.openOutputStream(Uri.parse(uri))?.use { out ->
                if (asPdf) exports.pdf(id, out) else exports.markdown(id, out)
            } ?: false
        }.getOrDefault(false)
    }

    suspend fun backupTo(uri: String): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching { app.contentResolver.openOutputStream(Uri.parse(uri))?.use { backups.backup(it) } != null }
            .getOrDefault(false)
        if (ok) settings.update { it.copy(lastBackupMillis = System.currentTimeMillis()) }
        else runCatching { DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(uri)) } // no half a backup left behind
        ok
    }

    /**
     * Replaces everything with the backup at [uri] and restarts at once: nothing still open may write over what came
     * back (a page's ink in memory, cached preferences, the old database). Leaving Settings meanwhile changes nothing.
     * False: not a Nisaba backup, nothing touched.
     */
    suspend fun restoreFrom(uri: String): Boolean = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
        val ok = runCatching { app.contentResolver.openInputStream(Uri.parse(uri))?.use { backups.restore(it) } ?: false }
            .getOrDefault(false)
        if (ok) restartApp(app)
        ok
    }

    /** Weekly backups go to [treeUri] (a folder the person picked; the permission is kept). */
    suspend fun setBackupFolder(treeUri: String) {
        val both = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { app.contentResolver.takePersistableUriPermission(Uri.parse(treeUri), both) }
        settings.update { it.copy(backupFolder = treeUri) }
        backupToFolderIfDue(force = true)
    }

    /** At start: a new zip in the backup folder when the last one is a week old (or [force]). */
    suspend fun backupToFolderIfDue(force: Boolean = false) {
        val s = settings.observe().first()
        if (s.backupFolder.isBlank() || (!force && System.currentTimeMillis() - s.lastBackupMillis < WEEK_MILLIS)) return
        val tree = Uri.parse(s.backupFolder)
        val folder = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }.getOrNull() ?: return
        val name = "nisaba-" + java.time.LocalDate.now() + ".zip"
        val file = runCatching {
            DocumentsContract.createDocument(app.contentResolver, folder, "application/zip", name)
        }.getOrNull() ?: return
        backupTo(file.toString())
    }

    private companion object { const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000 }
}

/** A fresh process on the library (after a restore: every open store and cache is stale). */
fun restartApp(context: Context) {
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
    Runtime.getRuntime().exit(0)
}
