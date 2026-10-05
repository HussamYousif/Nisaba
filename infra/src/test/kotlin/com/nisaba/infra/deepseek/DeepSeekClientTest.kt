package com.nisaba.infra.deepseek

import com.nisaba.domain.conversation.ImageDetail
import com.nisaba.domain.conversation.OracleEvent
import com.nisaba.domain.conversation.OracleException
import com.nisaba.domain.conversation.OracleImage
import com.nisaba.domain.conversation.OracleOptions
import com.nisaba.domain.conversation.OracleRequest
import com.nisaba.domain.conversation.Turn
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.NetworkKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeepSeekClientTest {
    private val server = MockWebServer().apply { start() }
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @AfterTest fun stop() { server.shutdown() }

    private fun client(key: String? = "sk-test", retries: Int = 2, firstTokenMillis: Long = 45_000) = DeepSeekClient(
        http, { key }, baseUrl = server.url("/").toString().trimEnd('/'), encodeImage = { "B64:${it.size}" },
        maxRetries = retries, retryDelayMillis = 10, firstTokenMillis = firstTokenMillis,
    )

    private fun request(
        thinking: Boolean = false,
        history: List<Turn> = emptyList(),
        doc: String? = null,
        memory: String? = null,
    ) = OracleRequest(
        system = "SYS", documentContext = doc, history = history, instruction = "look",
        images = listOf(OracleImage(byteArrayOf(1, 2, 3), ImageDetail.Auto), OracleImage(byteArrayOf(9), ImageDetail.High)),
        options = OracleOptions("deepseek-flash", thinking), memoryContext = memory,
    )

    private fun sse(vararg data: String) = data.joinToString("") { "data: $it\n\n" }
    private fun chunk(text: String) = """{"choices":[{"delta":{"content":${JsonPrimitive(text)}}}]}"""

    @Test fun streamsDeltasAndUsageEvenWhenBytesArriveOneAtATime() = runBlocking {
        val body = ": keep-alive\n\n" + sse(
            chunk("<answer>"), """{"choices":[{"delta":{"reasoning_content":"thinking…"}}]}""", chunk("Oslo."),
            """{"choices":[],"usage":{"prompt_tokens":1465,"completion_tokens":20,"prompt_cache_hit_tokens":1024}}""", "[DONE]",
        )
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(body, 1))
        val events = client().ask(request()).toList()
        assertEquals(
            listOf(OracleEvent.Delta("<answer>"), OracleEvent.Delta("Oslo."), OracleEvent.Usage(1465, 20, 1024)),
            events,
        )
    }

    @Test fun sendsTheDocumentedWireFormat() = runBlocking {
        server.enqueue(MockResponse().setBody(sse(chunk("x"), "[DONE]")))
        client().ask(request(history = listOf(Turn.User("q1"), Turn.Assistant("a1")), doc = "[p.1] hello")).toList()
        val rec = server.takeRequest()
        assertEquals("Bearer sk-test", rec.getHeader("Authorization"))
        assertEquals("/chat/completions", rec.path)
        val body = Json.parseToJsonElement(rec.body.readUtf8()).jsonObject
        assertEquals("deepseek-flash", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(body["reasoning_effort"])
        val msgs = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "user"), msgs.map { it["role"]!!.jsonPrimitive.content })
        assertTrue(msgs[0]["content"]!!.jsonPrimitive.content.contains("[p.1] hello"))
        // images only in the final user message, page first (auto) then focus (high)
        assertTrue(msgs.dropLast(1).all { it["content"] !is JsonArray })
        val parts = msgs.last()["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "image_url", "image_url"), parts.map { it["type"]!!.jsonPrimitive.content })
        val img = parts[1]["image_url"]!!.jsonObject
        assertEquals("data:image/jpeg;base64,B64:3", img["url"]!!.jsonPrimitive.content)
        assertEquals("auto", img["detail"]!!.jsonPrimitive.content)
        assertEquals("high", parts[2]["image_url"]!!.jsonObject["detail"]!!.jsonPrimitive.content)
    }

    @Test fun memoryFollowsTheDocumentInTheSystemMessage() = runBlocking {
        server.enqueue(MockResponse().setBody(sse(chunk("x"), "[DONE]")))
        client().ask(request(doc = "[p.1] hello", memory = "## books\n#1 Loved Dune")).toList()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val system = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("SYS\n\n# Document\n[p.1] hello\n\n# Memory\n## books\n#1 Loved Dune", system)
    }

    @Test fun thinkingModeIsRequestedExplicitly() = runBlocking {
        server.enqueue(MockResponse().setBody(sse(chunk("x"), "[DONE]")))
        client().ask(request(thinking = true)).toList()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
    }

    private fun errorOf(code: Int): DomainError = runBlocking {
        server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":"x"}"""))
        assertFailsWith<OracleException> { client(retries = 0).ask(request()).toList() }.error
    }

    @Test fun serverErrorsAndRateLimitsBeforeTheAnswerAreRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody(sse(chunk("ok"), "[DONE]")))
        assertEquals(listOf(OracleEvent.Delta("ok")), client().ask(request()).toList())
        assertEquals(3, server.requestCount)
    }

    @Test fun aConnectionDroppedBeforeTheResponseIsRetried() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody(sse(chunk("ok"), "[DONE]")))
        assertEquals(listOf(OracleEvent.Delta("ok")), client().ask(request()).toList())
    }

    @Test fun retriesGiveUpWithTheLastError() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        val e = assertFailsWith<OracleException> { client().ask(request()).toList() }
        assertEquals(DomainError.Network(NetworkKind.Server), e.error)
        assertEquals(3, server.requestCount)
    }

    @Test fun keepAlivesWithoutATokenEndInATimeout() = runBlocking {
        // the server only ever sends comments; each one would reset a plain read timeout
        server.enqueue(MockResponse().setBody(": keep-alive\n\n".repeat(200)).throttleBody(16, 50, TimeUnit.MILLISECONDS))
        val started = System.nanoTime()
        val e = assertFailsWith<OracleException> { client(firstTokenMillis = 300).ask(request()).toList() }
        assertEquals(DomainError.Network(NetworkKind.Timeout), e.error)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }

    @Test fun httpStatusesMapToDomainErrors() {
        assertEquals(DomainError.Auth, errorOf(401))
        assertEquals(DomainError.Quota, errorOf(402))
        assertEquals(DomainError.Network(NetworkKind.RateLimited), errorOf(429))
        assertEquals(DomainError.Network(NetworkKind.Server), errorOf(503))
        assertTrue(errorOf(400) is DomainError.Invalid)
    }

    @Test fun missingKeyFailsWithoutTouchingTheNetwork() = runBlocking {
        val e = assertFailsWith<OracleException> { client(key = null).ask(request()).toList() }
        assertEquals(DomainError.Auth, e.error)
        assertEquals(0, server.requestCount)
    }

    @Test fun droppedConnectionMidStreamIsANetworkError() = runBlocking {
        server.enqueue(
            MockResponse().setBody(sse(chunk("partial"), chunk("more"))).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val got = mutableListOf<OracleEvent>()
        val e = runCatching { client().ask(request()).collect { got += it } }.exceptionOrNull()
        assertTrue(e is OracleException && e.error is DomainError.Network, "error=$e")
    }

    @Test fun malformedChunksAreSkipped() = runBlocking {
        server.enqueue(MockResponse().setBody(sse("{not json", chunk("ok"), "[DONE]")))
        assertEquals(listOf(OracleEvent.Delta("ok")), client().ask(request()).toList())
    }

    @Test fun cancellingTheCollectorAbortsTheCallImmediately() = runBlocking {
        // ~30 s of trickling body; a cancel that did not abort the socket would take that long to return.
        val longBody = sse(*Array(200) { chunk("token$it ") })
        server.enqueue(MockResponse().setBody(longBody).throttleBody(64, 100, TimeUnit.MILLISECONDS))
        val started = System.nanoTime()
        val first = withTimeout(5_000) { client().ask(request()).first() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(first is OracleEvent.Delta)
        assertTrue(elapsedMs < 3_000, "returned after ${elapsedMs}ms")
    }
}
