package com.nisaba.domain.book

import com.nisaba.domain.shared.NotebookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeBookStore : BookStore {
    val maps = MutableStateFlow<Map<NotebookId, BookMap>>(emptyMap())
    val saved = mutableListOf<BookMap>()
    override suspend fun get(notebookId: NotebookId): BookMap? = maps.value[notebookId]
    override fun observe(notebookId: NotebookId): Flow<BookMap?> = maps.map { it[notebookId] }
    override suspend fun save(notebookId: NotebookId, map: BookMap) {
        saved += map
        maps.value = maps.value + (notebookId to map)
    }
}
