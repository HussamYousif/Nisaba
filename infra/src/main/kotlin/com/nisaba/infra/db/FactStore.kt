package com.nisaba.infra.db

import com.nisaba.domain.memory.Fact
import com.nisaba.domain.memory.FactId
import com.nisaba.domain.memory.MemoryStore
import com.nisaba.domain.memory.NewFact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomFactStore(private val dao: FactDao) : MemoryStore {
    companion object {
        /** Opens the shared on-disk database; keeps Room types out of the app module. */
        fun open(context: android.content.Context) = RoomFactStore(NisabaDatabase.shared(context).facts())
    }

    override suspend fun all(): List<Fact> = dao.all().map { it.toModel() }

    override fun observeAll(): Flow<List<Fact>> = dao.observe().map { rows -> rows.map { it.toModel() } }

    override suspend fun apply(add: List<NewFact>, forget: List<FactId>) = dao.apply(add.map { it.toDto() }, forget.map { it.value })
}
