package com.nisaba.domain.conversation

class RecordExchangeUseCase(private val store: ExchangeStore) {
    suspend operator fun invoke(exchange: Exchange) = store.append(exchange)
}
