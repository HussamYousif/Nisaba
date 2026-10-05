package com.nisaba.domain.memory

class ForgetFactUseCase(private val store: MemoryStore) {
    suspend operator fun invoke(id: FactId) = store.apply(emptyList(), listOf(id))
}
