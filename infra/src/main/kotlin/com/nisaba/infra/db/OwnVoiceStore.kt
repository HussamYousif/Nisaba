package com.nisaba.infra.db

import com.nisaba.domain.persona.OwnVoiceStore
import com.nisaba.domain.persona.Persona
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomOwnVoiceStore(private val dao: OwnVoiceDao, private val now: () -> Long = System::currentTimeMillis) : OwnVoiceStore {
    companion object {
        /** Opens the shared on-disk database; keeps Room types out of the app module. */
        fun open(context: android.content.Context) = RoomOwnVoiceStore(NisabaDatabase.shared(context).ownVoices())
    }

    override fun observe(): Flow<List<Persona>> = dao.observe().map { rows -> rows.map { it.toModel() } }

    override suspend fun save(voice: Persona) = dao.upsert(voice.toDto(now()))

    override suspend fun delete(id: String) = dao.delete(id)
}
