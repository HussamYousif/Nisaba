package com.nisaba.infra.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nisaba.domain.settings.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API key encrypted with an Android Keystore AES-256-GCM key. [devFallback] (debug builds: the `.env` key)
 * is returned only when the user has not saved a key; release builds pass an empty string.
 */
class KeystoreSecretStore(context: Context, private val devFallback: String = "") : SecretStore {
    private val prefs = context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    override suspend fun apiKey(): String? = withContext(Dispatchers.IO) { // Keystore and prefs touch disk
        val stored = prefs.getString(PREF_KEY, null)?.let { runCatching { decrypt(it) }.getOrNull() }
        stored ?: devFallback.ifBlank { null }
    }

    override suspend fun setApiKey(key: String?) = withContext(Dispatchers.IO) {
        prefs.edit().apply { if (key.isNullOrBlank()) remove(PREF_KEY) else putString(PREF_KEY, encrypt(key.trim())) }.apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS).build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(plain.toByteArray())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val (iv, ct) = blob.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
        return String(c.doFinal(ct))
    }

    private companion object {
        const val ALIAS = "nisaba.apikey"
        const val PREF_KEY = "deepseek"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
    }
}
