package com.nisaba.infra.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nisaba.domain.ink.EraseMode
import com.nisaba.domain.settings.AnswerInk
import com.nisaba.domain.settings.HandSpeed
import com.nisaba.domain.settings.HandStyle
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsData by preferencesDataStore("settings")

private object Keys {
    val pause = intPreferencesKey("pauseMillis")
    val speed = stringPreferencesKey("handSpeed")
    val style = stringPreferencesKey("handStyle")
    val ink = stringPreferencesKey("answerInk")
    val model = stringPreferencesKey("model")
    val thinking = booleanPreferencesKey("thinking")
    val listening = booleanPreferencesKey("listeningDefault")
    val eraser = stringPreferencesKey("eraserMode")
    val onboarded = booleanPreferencesKey("onboarded")
    val hintSeen = booleanPreferencesKey("hintSeen")
    val codeHighlight = stringPreferencesKey("codeHighlight")
    val penInk = intPreferencesKey("penInk")
    val penSize = stringPreferencesKey("penSize")
    val lastPersona = stringPreferencesKey("lastPersona")
}

private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E = enumValues<E>().firstOrNull { it.name == this } ?: default

class DataStoreSettingsStore(context: Context) : SettingsStore {
    private val store = context.applicationContext.settingsData

    override fun observe(): Flow<Settings> = store.data.map { it.toSettings() }

    /** Read and write in one `edit`: DataStore runs edits one at a time, so concurrent updates never undo each other. */
    override suspend fun update(transform: (Settings) -> Settings) {
        store.edit {
            val next = transform(it.toSettings())
            it[Keys.pause] = next.pauseMillis.coerceIn(Settings.MIN_PAUSE, Settings.MAX_PAUSE)
            it[Keys.speed] = next.handSpeed.name; it[Keys.style] = next.handStyle.name; it[Keys.ink] = next.answerInk.name
            it[Keys.model] = next.model; it[Keys.thinking] = next.thinking
            it[Keys.listening] = next.listeningDefault; it[Keys.eraser] = next.eraserMode.name
            it[Keys.onboarded] = next.onboarded; it[Keys.hintSeen] = next.hintSeen
            it[Keys.codeHighlight] = next.codeHighlight.name; it[Keys.penInk] = next.penInk; it[Keys.penSize] = next.penSize.name
            it[Keys.lastPersona] = next.lastPersona
        }
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            pauseMillis = this[Keys.pause] ?: d.pauseMillis,
            handSpeed = this[Keys.speed].toEnum(d.handSpeed), handStyle = this[Keys.style].toEnum(d.handStyle),
            answerInk = this[Keys.ink].toEnum(d.answerInk),
            model = this[Keys.model] ?: d.model, thinking = this[Keys.thinking] ?: d.thinking,
            listeningDefault = this[Keys.listening] ?: d.listeningDefault, eraserMode = this[Keys.eraser].toEnum(d.eraserMode),
            onboarded = this[Keys.onboarded] ?: d.onboarded, hintSeen = this[Keys.hintSeen] ?: d.hintSeen,
            codeHighlight = this[Keys.codeHighlight].toEnum(d.codeHighlight), penInk = this[Keys.penInk] ?: d.penInk,
            penSize = this[Keys.penSize].toEnum(d.penSize), lastPersona = this[Keys.lastPersona] ?: d.lastPersona,
        )
    }
}
