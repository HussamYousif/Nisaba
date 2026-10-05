package com.nisaba.domain.memory

import kotlinx.coroutines.flow.Flow

class ObserveMemoryUseCase(private val store: MemoryStore) {
    operator fun invoke(): Flow<List<Fact>> = store.observeAll()
}
