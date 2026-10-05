package com.nisaba.infra.db

import com.nisaba.domain.conversation.Exchange
import com.nisaba.domain.conversation.ExchangeStore
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.usage.ExchangeUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomExchangeStore(private val dao: ExchangeDao) : ExchangeStore {
    companion object {
        /** Opens the shared on-disk database; keeps Room types out of the app module. */
        fun open(context: android.content.Context) = RoomExchangeStore(NisabaDatabase.shared(context).exchanges())
    }

    override suspend fun append(exchange: Exchange) = dao.insert(exchange.toDto())

    override suspend fun listByNotebook(id: NotebookId): List<Exchange> = dao.list(id.value).map { it.toModel() }

    override fun observeByNotebook(id: NotebookId): Flow<List<Exchange>> = dao.observe(id.value).map { rows -> rows.map { it.toModel() } }

    override suspend fun get(id: ExchangeId): Exchange? = dao.get(id.value)?.toModel()

    override suspend fun search(query: String, limit: Int): List<Exchange> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return dao.search(like, limit).map { it.toModel() }
    }

    override suspend fun usage(): List<ExchangeUsage> = dao.usage().map { it.toModel() }
}
