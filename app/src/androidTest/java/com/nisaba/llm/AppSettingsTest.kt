package com.nisaba.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AppSettings (audit P2-1, P2-4, P3-3): key fallback, URL normalization,
 * clamping, and the debug-vs-release cleartext policy. Settings persist to
 * app prefs, so every test restores the values it touches.
 */
@RunWith(AndroidJUnit4::class)
class AppSettingsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun storedKey_winsOverDevKey() {
        val s = AppSettings(ctx)
        try {
            s.apiKey = "sk-stored-key"
            assertEquals("sk-stored-key", s.effectiveApiKey())
        } finally {
            s.apiKey = ""
        }
    }

    @Test
    fun blankStoredKey_fallsBackToDevKey() {
        val s = AppSettings(ctx)
        s.apiKey = ""
        assertEquals(com.nisaba.BuildConfig.DEEPSEEK_API_KEY, s.effectiveApiKey())
    }

    @Test
    fun baseUrl_normalizesTrailingSlash() {
        val s = AppSettings(ctx)
        try {
            s.baseUrl = "https://api.deepseek.com/"
            assertEquals("https://api.deepseek.com/chat/completions", s.chatCompletionsUrl())
        } finally {
            s.baseUrl = "https://api.deepseek.com"
        }
    }

    @Test
    fun idleSeconds_areClamped() {
        val s = AppSettings(ctx)
        try {
            s.idlePauseSeconds = 99
            assertEquals(30, s.idlePauseSeconds)
            s.idlePauseSeconds = 0
            assertEquals(1, s.idlePauseSeconds)
        } finally {
            s.idlePauseSeconds = 3
        }
    }

    @Test
    fun debugBuild_allowsCleartextForMockServers() {
        // The https guard only bites in release; debug must keep http for the
        // instrumented-test mock servers (audit P2-1).
        val s = AppSettings(ctx)
        try {
            s.baseUrl = "http://127.0.0.1:8080"
            assertEquals("http://127.0.0.1:8080/chat/completions", s.chatCompletionsUrl())
        } finally {
            s.baseUrl = "https://api.deepseek.com"
        }
    }
}
