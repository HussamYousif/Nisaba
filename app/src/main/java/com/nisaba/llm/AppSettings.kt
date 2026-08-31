package com.nisaba.llm

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * App settings (docs/02 F7, docs/03 §7): the DeepSeek API key lives in
 * Keystore-backed EncryptedSharedPreferences; everything else is plain prefs.
 */
class AppSettings(context: Context) {

    private val secure: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            "nisaba_secure",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val plain: SharedPreferences =
        context.getSharedPreferences("nisaba_prefs", Context.MODE_PRIVATE)

    var apiKey: String
        get() = secure.getString(KEY_API_KEY, "") ?: ""
        set(value) = secure.edit().putString(KEY_API_KEY, value.trim()).apply()

    var baseUrl: String
        get() = plain.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = plain.edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()

    var model: String
        get() = plain.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = plain.edit().putString(KEY_MODEL, value.trim()).apply()

    var temperature: Float
        get() = plain.getFloat(KEY_TEMPERATURE, 0.7f)
        set(value) = plain.edit().putFloat(KEY_TEMPERATURE, value).apply()

    var maxTokens: Int
        get() = plain.getInt(KEY_MAX_TOKENS, 4096)
        set(value) = plain.edit().putInt(KEY_MAX_TOKENS, value).apply()

    var autoSummonEnabled: Boolean
        get() = plain.getBoolean(KEY_AUTO_SUMMON, true)
        set(value) = plain.edit().putBoolean(KEY_AUTO_SUMMON, value).apply()

    var idlePauseSeconds: Int
        get() = plain.getInt(KEY_IDLE_SECONDS, 3)
        set(value) = plain.edit().putInt(KEY_IDLE_SECONDS, value.coerceIn(1, 30)).apply()

    var soundsEnabled: Boolean
        get() = plain.getBoolean(KEY_SOUNDS, false)
        set(value) = plain.edit().putBoolean(KEY_SOUNDS, value).apply()

    /** Normalized chat-completions URL for the configured base. */
    fun chatCompletionsUrl(): String = "$baseUrl/chat/completions"

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-v4-flash-vision-exp"

        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_AUTO_SUMMON = "auto_summon"
        private const val KEY_IDLE_SECONDS = "idle_seconds"
        private const val KEY_SOUNDS = "sounds"
    }
}
