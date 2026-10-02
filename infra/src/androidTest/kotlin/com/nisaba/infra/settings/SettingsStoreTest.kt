package com.nisaba.infra.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.domain.ink.EraseMode
import com.nisaba.domain.settings.AnswerInk
import com.nisaba.domain.settings.HandSpeed
import com.nisaba.domain.settings.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun defaultsThenUpdateRoundTripAndPauseIsClamped() = runBlocking {
        val store = DataStoreSettingsStore(ctx)
        store.update { Settings() }
        assertEquals(Settings(), store.observe().first())
        store.update { it.copy(handSpeed = HandSpeed.Swift, answerInk = AnswerInk.Black, eraserMode = EraseMode.Area, pauseMillis = 99_999) }
        val s = store.observe().first()
        assertEquals(HandSpeed.Swift, s.handSpeed); assertEquals(AnswerInk.Black, s.answerInk)
        assertEquals(EraseMode.Area, s.eraserMode); assertEquals(Settings.MAX_PAUSE, s.pauseMillis)
        store.update { Settings() }
    }

    @Test fun secretRoundTripsEncryptedAndFallsBackToDevKey() = runBlocking {
        val plain = KeystoreSecretStore(ctx)
        plain.setApiKey(null)
        assertNull(plain.apiKey())
        assertEquals("dev", KeystoreSecretStore(ctx, devFallback = "dev").apiKey())
        plain.setApiKey("sk-test-123")
        assertEquals("sk-test-123", plain.apiKey())
        assertEquals("sk-test-123", KeystoreSecretStore(ctx, devFallback = "dev").apiKey())
        val raw = ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE).getString("deepseek", "")!!
        assert(!raw.contains("sk-test-123")) { "key must not be stored in clear text" }
        plain.setApiKey(null)
        assertNull(plain.apiKey())
    }
}
