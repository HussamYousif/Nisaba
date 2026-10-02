package com.nisaba.infra.inkfile

import com.nisaba.domain.ink.InkStore
import com.nisaba.domain.ink.PageInk
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.shared.Logger
import com.nisaba.domain.shared.PageId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * and debounced; reads fall back main → `.bak` → empty. Reads see pending (unsaved) writes.
 */
@OptIn(ExperimentalSerializationApi::class)
class InkFileStore(
    private val dir: File,
    private val scope: CoroutineScope,
    private val logger: Logger? = null,
    private val debounceMillis: Long = 500,
) : InkStore {
    private val pending = HashMap<PageId, List<Stroke>>()
    private val jobs = HashMap<PageId, Job>()
    private val lock = Mutex()
    private val proto = ProtoBuf

    init { dir.mkdirs() }

    private fun main(id: PageId) = File(dir, "${id.value}.ink")
    private fun bak(id: PageId) = File(dir, "${id.value}.ink.bak")
    private fun tmp(id: PageId) = File(dir, "${id.value}.ink.tmp")

    override suspend fun load(pageId: PageId): PageInk = lock.withLock {
        val strokes = pending[pageId] ?: readOrNull(main(pageId), pageId) ?: readOrNull(bak(pageId), pageId).orEmpty()
        PageInk(pageId, strokes)
    }

    override suspend fun save(pageId: PageId, strokes: List<Stroke>) {
        lock.withLock {
            pending[pageId] = strokes
            jobs[pageId]?.cancel()
            jobs[pageId] = scope.launch {
                delay(debounceMillis)
                lock.withLock { writePending(pageId) }
            }
        }
    }

    /** Writes everything pending right now (call from onPause). */
    suspend fun flush() = lock.withLock {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        pending.keys.toList().forEach { writePending(it) }
    }

    override suspend fun deletePages(ids: List<PageId>) = lock.withLock {
        ids.forEach { id ->
            jobs.remove(id)?.cancel()
            pending.remove(id)
            main(id).delete(); bak(id).delete(); tmp(id).delete()
        }
    }

    private fun writePending(id: PageId) {
        val strokes = pending.remove(id) ?: return
        jobs.remove(id)
        runCatching { writeAtomic(id, strokes) }
            .onFailure {
                pending[id] = strokes // keep in memory; next save/flush retries
                logger?.e("InkFileStore", "write failed for ${id.value}", it)
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
        if (!file.exists()) return null
        return runCatching {
            val dto = proto.decodeFromByteArray<InkFileDto>(file.readBytes())
            require(dto.version <= INK_FILE_VERSION) { "ink file version ${dto.version} is newer than supported" }
            dto.toModels()
        }.onFailure { logger?.w("InkFileStore", "unreadable ${file.name} for ${id.value}", it) }.getOrNull()
    }
}
