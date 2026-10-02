package com.nisaba.infra.db

import com.nisaba.domain.conversation.Exchange
import com.nisaba.domain.conversation.ExchangeStore
import com.nisaba.domain.shared.NotebookId
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
}
