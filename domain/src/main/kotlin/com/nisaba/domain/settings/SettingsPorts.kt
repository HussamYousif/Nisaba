package com.nisaba.domain.settings

import kotlinx.coroutines.flow.Flow

interface SettingsStore {
    fun observe(): Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}

interface SecretStore {
    suspend fun apiKey(): String?
    suspend fun setApiKey(key: String?)
}
