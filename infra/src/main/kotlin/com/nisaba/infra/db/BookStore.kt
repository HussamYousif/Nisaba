package com.nisaba.infra.db

import com.nisaba.domain.book.BookMap
import com.nisaba.domain.book.BookStore
import com.nisaba.domain.shared.NotebookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class RoomBookStore(private val dao: BookMapDao) : BookStore {
    override suspend fun get(notebookId: NotebookId): BookMap? = dao.get(notebookId.value)?.let(::decode)
    override fun observe(notebookId: NotebookId): Flow<BookMap?> = dao.observe(notebookId.value).map { row -> row?.let(::decode) }
    override suspend fun save(notebookId: NotebookId, map: BookMap) =
        dao.upsert(BookMapRowDto(notebookId.value, JSON.encodeToString(BookMapDto.serializer(), map.toDto())))

    /** A row that no longer reads (a future format) is no map: the book is read again rather than the app failing. */
    private fun decode(row: BookMapRowDto): BookMap? =
        runCatching { JSON.decodeFromString(BookMapDto.serializer(), row.json).toModel() }.getOrNull()

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun open(context: android.content.Context) = RoomBookStore(NisabaDatabase.shared(context).books())
    }
}
