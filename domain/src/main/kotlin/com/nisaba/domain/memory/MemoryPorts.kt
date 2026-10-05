package com.nisaba.domain.memory

import kotlinx.coroutines.flow.Flow

/** The person's long-term memory, shared by every notebook. Ids are assigned by the store on insert. */
interface MemoryStore {
    suspend fun all(): List<Fact>
    fun observeAll(): Flow<List<Fact>>

    /** Drops [forget] and inserts [add] in one transaction. */
    suspend fun apply(add: List<NewFact>, forget: List<FactId>)
}
