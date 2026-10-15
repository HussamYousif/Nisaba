package com.nisaba.infra.deepseek

import com.nisaba.domain.conversation.Oracle
import com.nisaba.domain.conversation.OracleEvent
import com.nisaba.domain.conversation.OracleException
import com.nisaba.domain.conversation.OracleRequest
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.NetworkKind
import com.nisaba.infra.http.SseParser
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * DeepSeek chat-completions adapter (OpenAI-compatible, SSE). Collecting starts the call; cancelling the
 * collector cancels the OkHttp call, which closes the socket immediately.
 *
 * Failures before the answer starts (no connection, 429, 5xx) are retried [maxRetries] times with a short backoff.
 * Once streaming, a reply must produce its first token within [firstTokenMillis] (keep-alive comments don't count),
 * and the whole call is capped, so a stalled server ends in a Timeout instead of an endless wait.
 */
class DeepSeekClient(
    private val http: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val baseUrl: String = "https://api.deepseek.com",
    private val encodeImage: ((ByteArray) -> String)? = null,
    private val maxRetries: Int = 2,
    private val retryDelayMillis: Long = 500,
    private val firstTokenMillis: Long = 45_000,
    private val thinkingFirstTokenMillis: Long = 120_000,
) : Oracle {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val sse = SseParser()

    override fun ask(request: OracleRequest): Flow<OracleEvent> = flow {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw OracleException(DomainError.Auth, "no API key")
        val wire = if (encodeImage != null) request.toWire(encodeImage) else request.toWire()
        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Accept", "text/event-stream")
            .post(json.encodeToString(wire).toRequestBody("application/json".toMediaType()))
            .build()
        val thinking = request.options.thinking
        val client = http.newBuilder()
            .readTimeout(if (thinking) THINKING_READ_SECONDS else FIRST_TOKEN_SECONDS, TimeUnit.SECONDS)
            .callTimeout(if (thinking) THINKING_CALL_SECONDS else CALL_SECONDS, TimeUnit.SECONDS)
            .build()
        var attempt = 0
        while (true) {
            val call = client.newCall(httpRequest)
            when (val a = attempt(call, attempt)) {
                is Attempt.Retry -> { attempt++; delay(a.waitMillis) }
                is Attempt.Got -> { stream(call, a.response, if (thinking) thinkingFirstTokenMillis else firstTokenMillis); return@flow }
            }
        }
    }.flowOn(Dispatchers.IO)

    private sealed interface Attempt {
        class Got(val response: Response) : Attempt
        class Retry(val waitMillis: Long) : Attempt
    }

    /** One try at getting response headers; a transient failure becomes [Attempt.Retry] while retries are left. */
    private suspend fun attempt(call: Call, n: Int): Attempt {
        val response = try {
            call.await()
        } catch (e: InterruptedIOException) {
            return failed(e, NetworkKind.Timeout, n)
        } catch (e: IOException) {
            return failed(e, NetworkKind.Offline, n)
        }
        val transient = response.code == HTTP_TOO_MANY || response.code >= HTTP_SERVER_ERROR
        if (!transient || n >= maxRetries) return Attempt.Got(response)
        val wait = backoff(n, response.header("Retry-After"))
        response.close()
        return Attempt.Retry(wait)
    }

    private suspend fun failed(e: IOException, kind: NetworkKind, n: Int): Attempt {
        currentCoroutineContext().ensureActive()
        if (n >= maxRetries) throw OracleException(DomainError.Network(kind), e.message, e)
        return Attempt.Retry(backoff(n, null))
    }

    private fun backoff(attempt: Int, retryAfter: String?): Long =
        retryAfter?.trim()?.toLongOrNull()?.let { (it * MILLIS).coerceAtMost(MAX_RETRY_AFTER_MILLIS) }
            ?: (retryDelayMillis * (1 + attempt * 2))

    private suspend fun FlowCollector<OracleEvent>.stream(call: Call, response: Response, firstTokenMs: Long) = coroutineScope {
        val produced = AtomicBoolean(false)
        val stalled = AtomicBoolean(false)
        // Cancelling the collector cancels this scope; the watcher's finally then aborts the socket at once.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
        val watchdog = launch {
            delay(firstTokenMs)
            if (!produced.get()) { stalled.set(true); call.cancel() }
        }
        try {
            readStream(response, produced)
        } catch (e: InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            throw OracleException(DomainError.Network(NetworkKind.Timeout), e.message, e)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw OracleException(DomainError.Network(if (stalled.get()) NetworkKind.Timeout else NetworkKind.Offline), e.message, e)
        } finally {
            watcher.cancel(); watchdog.cancel()
        }
    }

    private suspend fun FlowCollector<OracleEvent>.readStream(response: Response, produced: AtomicBoolean) {
        response.use {
            val source = openStream(response)
            var parseFailure = false
            var any = false
            sse.read(source) { data ->
                if (data == "[DONE]") return@read false
                val events = runCatching { json.decodeFromString<ChatChunkDto>(data).toEvents() }
                    .onFailure { parseFailure = true } // a malformed chunk is skipped; fatal only if nothing ever parses
                    .getOrDefault(emptyList())
                if (events.isNotEmpty()) any = true
                // thinking out loud counts as progress: a long think is not a stalled server
                if (events.any { it is OracleEvent.Delta } || isReasoning(data)) produced.set(true)
                events.forEach { emit(it) }
                true
            }
            if (!any && parseFailure) throw OracleException(DomainError.Network(NetworkKind.Server), "unparseable stream")
        }
    }

    private fun isReasoning(data: String) =
        runCatching { json.decodeFromString<ChatChunkDto>(data).choices.firstOrNull()?.delta?.reasoning?.isNotEmpty() == true }
            .getOrDefault(false)

    private fun openStream(response: Response): BufferedSource {
        if (!response.isSuccessful) throw OracleException(mapStatus(response.code, response.body?.string()))
        return response.body?.source() ?: throw OracleException(DomainError.Network(NetworkKind.Server), "empty body")
    }

    private fun mapStatus(code: Int, body: String?): DomainError = when {
        code == HTTP_UNAUTHORIZED -> DomainError.Auth
        code == HTTP_PAYMENT_REQUIRED -> DomainError.Quota
        code == HTTP_TOO_MANY -> DomainError.Network(NetworkKind.RateLimited)
        code >= HTTP_SERVER_ERROR -> DomainError.Network(NetworkKind.Server)
        else -> DomainError.Invalid("HTTP $code ${body.orEmpty().take(MAX_ERROR_BODY)}")
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = cont.resume(response)
            override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
        })
        cont.invokeOnCancellation { cancel() }
    }

    private companion object {
        const val FIRST_TOKEN_SECONDS = 45L
        const val THINKING_READ_SECONDS = 120L
        const val CALL_SECONDS = 180L
        const val THINKING_CALL_SECONDS = 420L
        const val MILLIS = 1000L
        const val MAX_RETRY_AFTER_MILLIS = 5_000L
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_PAYMENT_REQUIRED = 402
        const val HTTP_TOO_MANY = 429
        const val HTTP_SERVER_ERROR = 500
        const val MAX_ERROR_BODY = 200
    }
}
