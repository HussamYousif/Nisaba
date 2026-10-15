package com.nisaba.domain.persona

import kotlinx.coroutines.flow.Flow

/** The person's own voices (DESIGN §5.3a), oldest first. */
interface OwnVoiceStore {
    fun observe(): Flow<List<Persona>>
    suspend fun save(voice: Persona)
    suspend fun delete(id: String)
}
