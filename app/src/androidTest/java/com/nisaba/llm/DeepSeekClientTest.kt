package com.nisaba.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DeepSeek client against an in-process mock server (docs/07 §1–3):
 * request shape (auth header, model, vision data-URI), error mapping and
 * cancellation.
 */
@RunWith(AndroidJUnit4::class)
class DeepSeekClientTest {

    private lateinit var server: MockWebServer
    private lateinit var settings: AppSettings

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        settings = AppSettings(ApplicationProvider.getApplicationContext<Context>())
        // Snapshot for @After restore — the tests write into the REAL app
        // prefs (audit P3-5).
        saved = listOf(settings.apiKey, settings.baseUrl, settings.model, settings.idlePauseSeconds, settings.autoSummonEnabled)
        settings.baseUrl = server.url("/").toString().trimEnd('/')
        settings.apiKey = "test-key-123"
    }

    private var saved: List<Any> = emptyList()

    @After
    fun tearDown() {
        server.shutdown()
        // Restore the real settings (audit P3-5): the app must not be left
        // pointing at a dead localhost mock with a bogus key.
        if (saved.size == 5) {
            settings.apiKey = saved[0] as String
            settings.baseUrl = saved[1] as String
            settings.model = saved[2] as String
            settings.idlePauseSeconds = saved[3] as Int
            settings.autoSummonEnabled = saved[4] as Boolean
        }
    }

    private fun completionJson(content: String) =
        """{"choices":[{"index":0,"message":{"role":"assistant","content":"${content.replace("\"", "\\\"")}"}}]}"""

    @Test
    fun sendsVisionRequest_withAuthAndParsesContent() = runBlocking {
        val blocks = """{"blocks":[{"type":"text","markdown":"The answer is 42"}]}"""
        server.enqueue(MockResponse().setBody(completionJson(blocks)))

        val client = DeepSeekClient(settings)
        val request = ChatContract.ChatRequest(
            model = "deepseek-v4-flash-vision-exp",
            messages = listOf(
                ChatContract.visionMessage("user", "What does this say?", "data:image/png;base64,AAAA")
            ),
        )
        val content = client.chat(request)
        assertEquals(blocks, content)

        val recorded = server.takeRequest()
        assertEquals("Bearer test-key-123", recorded.getHeader("Authorization"))
        assertEquals("/chat/completions", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue("model missing in body: $body", body.contains("\"model\":\"deepseek-v4-flash-vision-exp\""))
        assertTrue("image missing in body: $body", body.contains("data:image/png;base64,AAAA"))
        assertTrue("image_url type missing in body: $body", body.contains("\"type\":\"image_url\""))
        assertTrue("response_format missing in body: $body", body.contains("\"response_format\""))
    }

    @Test
    fun handlesStringContentResponse() = runBlocking {
        // Some providers return content as a plain string.
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"role":"assistant","content":"plain string answer"}}]}"""
        ))
        val content = DeepSeekClient(settings).chat(ChatContract.ChatRequest(model = "m"))
        assertEquals("plain string answer", content)
    }

    @Test
    fun mapsHttp401() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid key"}}"""))
        var caught: DeepSeekClient.ApiException? = null
        try {
            DeepSeekClient(settings).chat(ChatContract.ChatRequest(model = "m"))
        } catch (e: DeepSeekClient.ApiException) {
            caught = e
        }
        assertEquals(401, caught?.code)
    }

    @Test
    fun mapsRateLimit429() = runBlocking {
        // 429 is retryable (docs/07 §6): the client retries once after 5 s,
        // then surfaces the status when the retry also fails.
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"busy"}}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"busy"}}"""))
        var caught: DeepSeekClient.ApiException? = null
        try {
            DeepSeekClient(settings).chat(ChatContract.ChatRequest(model = "m"))
        } catch (e: DeepSeekClient.ApiException) {
            caught = e
        }
        assertEquals(429, caught?.code)
        assertEquals(2, server.requestCount) // one retry, then the failure
    }

    @Test
    fun retriesOnceOn5xx_thenSucceeds() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"overloaded"}}"""))
        server.enqueue(MockResponse().setBody(completionJson("""{"blocks":[]}""")))
        val content = DeepSeekClient(settings).chat(ChatContract.ChatRequest(model = "m"))
        assertEquals("""{"blocks":[]}""", content)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun nonRetryableStatus_doesNotRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"bad request"}}"""))
        try {
            DeepSeekClient(settings).chat(ChatContract.ChatRequest(model = "m"))
        } catch (_: DeepSeekClient.ApiException) {
        }
        assertEquals("400 must not retry", 1, server.requestCount)
    }

    @Test
    fun cancellation_abortsTheRequest() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS)
                .setBody(completionJson("""{"blocks":[]}"""))
        )
        val client = DeepSeekClient(settings)
        val job = launch {
            client.chat(ChatContract.ChatRequest(model = "m"))
        }
        delay(400) // let the request go out
        server.takeRequest() // the server received it…
        job.cancel() // …and we cancel (cancel-on-resume path)
        try {
            job.join()
        } catch (_: Exception) {
        }
        assertTrue("job must be cancelled", job.isCancelled)
    }
}
