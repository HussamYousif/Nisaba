package com.nisaba.llm

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * App settings (docs/02 F7, docs/03 §7): the DeepSeek API key lives in
 * Keystore-backed EncryptedSharedPreferences; everything else is plain prefs.
 */
class AppSettings(context: Context) {

    private val contextRef = context.applicationContext

    /**
     * Keystore-backed prefs. A lock-screen change can invalidate the master
     * key, after which create() throws on first access — without recovery the
     * app is bricked until data is cleared (audit P2-4). Wipe and rebuild once
     * instead: losing the stored key beats losing the app.
     */
    private val secure: SharedPreferences by lazy {
        try {
            createSecure(contextRef)
        } catch (e: Exception) {
            contextRef.deleteSharedPreferences("nisaba_secure")
            createSecure(contextRef)
        }
    }

    private fun createSecure(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "nisaba_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val plain: SharedPreferences =
        contextRef.getSharedPreferences("nisaba_prefs", Context.MODE_PRIVATE)

    var apiKey: String
        get() = secure.getString(KEY_API_KEY, "") ?: ""
        set(value) = secure.edit().putString(KEY_API_KEY, value.trim()).apply()

    /** Dev key from `.env`, injected into debug builds (never release). */
    val devApiKey: String
        get() = com.nisaba.BuildConfig.DEEPSEEK_API_KEY

    /** Stored key wins; falls back to the .env dev key so nothing needs typing. */
    fun effectiveApiKey(): String = apiKey.ifBlank { devApiKey }

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
    fun chatCompletionsUrl(): String {
        val url = "$baseUrl/chat/completions"
        // Release refuses cleartext outright: a typo'd http:// base would
        // send Authorization: Bearer sk-… in plaintext (audit P2-1). Debug
        // keeps http for the instrumented-test mock servers.
        if (!com.nisaba.BuildConfig.DEBUG && url.startsWith("http://")) {
            throw IllegalArgumentException("cleartext base URL is not allowed in release builds")
        }
        return url
    }

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
