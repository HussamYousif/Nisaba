package com.nisaba.domain.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [MemoryStore] with Room-like increasing ids. */
class FakeMemoryStore(initial: List<Fact> = emptyList()) : MemoryStore {
    val facts = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id.value } ?: 0) + 1

    override suspend fun all() = facts.value
    override fun observeAll(): Flow<List<Fact>> = facts

    override suspend fun apply(add: List<NewFact>, forget: List<FactId>) {
        val kept = facts.value.filter { it.id !in forget }
        facts.value = kept + add.map { Fact(FactId(nextId++), it.category, it.text, it.sourceExchangeId, it.createdAtMillis) }
    }
}

fun fact(id: Long, text: String, category: FactCategory = FactCategory.Other) = Fact(FactId(id), category, text, null, id)
