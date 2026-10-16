package com.nisaba.infra.inkfile

import com.nisaba.domain.ink.InkStore
import com.nisaba.domain.ink.PageInk
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.shared.Logger
import com.nisaba.domain.shared.PageId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One binary file per page: `<pageId>.ink`. Writes are atomic (temp + fsync + rename, previous version kept as `.bak`)
 * and debounced; reads fall back main → `.bak` → empty. Reads see pending (unsaved) writes. A file that exists but can't
 * be read (damaged, written by a newer version, too big to load) is renamed `.corrupt-<time>` before anything else
 * happens, so no later save can rotate it away; the page says so ([PageInk.setAside]).
 *
 * [lock] only guards the in-memory maps; file work runs on [io] under [diskLock], so a slow write never holds up a
 * save or a load (a page stays pending until its write has landed).
 */
@OptIn(ExperimentalSerializationApi::class)
/** However steady the writing, a stroke is on disk this long after it was made, at most (DESIGN §2: ≤ 1 s). */
private const val MAX_UNSAVED_MILLIS = 1_000L

class InkFileStore(
    private val dir: File,
    private val scope: CoroutineScope,
    private val logger: Logger? = null,
    private val debounceMillis: Long = 500,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : InkStore {
    private val pending = HashMap<PageId, List<Stroke>>()
    private val jobs = HashMap<PageId, Job>()

    /** When each page's oldest unsaved change came in: steady writing must not put saving off for ever. */
    private val dirtySince = HashMap<PageId, Long>()
    private val lock = Mutex()
    private val diskLock = Mutex()
    private val proto = ProtoBuf

    init { dir.mkdirs() }

    private fun main(id: PageId) = File(dir, "${id.value}.ink")
    private fun bak(id: PageId) = File(dir, "${id.value}.ink.bak")
    private fun tmp(id: PageId) = File(dir, "${id.value}.ink.tmp")

    override suspend fun load(pageId: PageId): PageInk {
        lock.withLock { pending[pageId] }?.let { return PageInk(pageId, it) }
        return diskLock.withLock { withContext(io) { readPage(pageId) } }
    }

    /** Main, else `.bak`, else nothing; whatever exists but can't be read is set aside on the way. */
    private fun readPage(id: PageId): PageInk {
        var setAside = false
        for (file in listOf(main(id), bak(id))) {
            if (!file.exists()) continue
            readOrNull(file, id)?.let { return PageInk(id, it, setAside) }
            setAside = setAside(file) || setAside
        }
        return PageInk(id, emptyList(), setAside)
    }

    private fun setAside(file: File): Boolean {
        val kept = File(dir, "${file.name}.corrupt-${System.currentTimeMillis()}")
        return file.renameTo(kept).also { ok -> logger?.w("InkFileStore", "kept aside ${file.name} as ${kept.name}: $ok") }
    }

    override suspend fun save(pageId: PageId, strokes: List<Stroke>) {
        lock.withLock {
            pending[pageId] = strokes
            jobs[pageId]?.cancel()
            val now = System.currentTimeMillis()
            val since = dirtySince.getOrPut(pageId) { now }
            // a pause in the writing saves after debounceMillis; however steady it is, a stroke is on disk within ~1 s
            val wait = debounceMillis.coerceAtMost((MAX_UNSAVED_MILLIS - (now - since)).coerceAtLeast(0))
            jobs[pageId] = scope.launch {
                delay(wait)
                writePending(pageId)
            }
        }
    }

    /** Writes everything pending right now (call from onPause). */
    suspend fun flush() {
        val ids = lock.withLock {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            pending.keys.toList()
        }
        ids.forEach { writePending(it) }
    }

    override suspend fun deletePages(ids: List<PageId>) {
        lock.withLock { ids.forEach { id -> jobs.remove(id)?.cancel(); pending.remove(id) } }
        diskLock.withLock {
            withContext(io) {
                ids.forEach { id ->
                    main(id).delete(); bak(id).delete(); tmp(id).delete()
                    dir.listFiles { f -> f.name.startsWith("${id.value}.ink.corrupt-") }?.forEach { it.delete() }
                }
            }
        }
    }

    private suspend fun writePending(id: PageId) = diskLock.withLock {
        val strokes = lock.withLock { pending[id] } ?: return@withLock
        val ok = withContext(io + NonCancellable) { // a write that started finishes: never leave a half-renamed page
            runCatching { writeAtomic(id, strokes) }
                .onFailure { logger?.e("InkFileStore", "write failed for ${id.value}", it) } // kept pending; next save/flush retries
                .isSuccess
        }
        if (ok) {
            lock.withLock {
                if (pending[id] === strokes) { pending.remove(id); dirtySince.remove(id) }
                else dirtySince[id] = System.currentTimeMillis() // newer ink came in meanwhile: its own second starts now
            }
        }
    }

    private fun writeAtomic(id: PageId, strokes: List<Stroke>) {
        val bytes = proto.encodeToByteArray(strokes.toFileDto())
        val t = tmp(id)
        FileOutputStream(t).use { out -> out.write(bytes); out.flush(); out.fd.sync() }
        val m = main(id)
        if (m.exists()) Files.move(m.toPath(), bak(id).toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.move(t.toPath(), m.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun readOrNull(file: File, id: PageId): List<Stroke>? {
        return runCatching { // also OutOfMemoryError: a page too big to load is kept aside, not lost
            val dto = proto.decodeFromByteArray<InkFileDto>(file.readBytes())
            require(dto.version <= INK_FILE_VERSION) { "ink file version ${dto.version} is newer than supported" }
            dto.toModels()
        }.onFailure { logger?.w("InkFileStore", "unreadable ${file.name} for ${id.value}", it) }.getOrNull()
    }
}
