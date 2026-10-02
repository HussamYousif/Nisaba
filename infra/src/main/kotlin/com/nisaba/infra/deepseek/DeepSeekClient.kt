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
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * DeepSeek chat-completions adapter (OpenAI-compatible, SSE). Collecting starts the call; cancelling the
 * collector cancels the OkHttp call, which closes the socket immediately.
 */
class DeepSeekClient(
    private val http: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val baseUrl: String = "https://api.deepseek.com",
    private val encodeImage: ((ByteArray) -> String)? = null,
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
        val readSeconds = if (request.options.thinking) THINKING_READ_SECONDS else FIRST_TOKEN_SECONDS
        val call = http.newBuilder().readTimeout(readSeconds, TimeUnit.SECONDS).build().newCall(httpRequest)
        coroutineScope {
            // Cancelling the collector cancels this scope; the watcher's finally then aborts the socket at once.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try {
                readStream(call)
            } catch (e: SocketTimeoutException) {
                currentCoroutineContext().ensureActive()
                throw OracleException(DomainError.Network(NetworkKind.Timeout), e.message, e)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw OracleException(DomainError.Network(NetworkKind.Offline), e.message, e)
            } finally {
                watcher.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<OracleEvent>.readStream(call: Call) {
        call.await().use { response ->
            val source = openStream(response)
            var parseFailure = false
            var produced = false
            sse.read(source) { data ->
                if (data == "[DONE]") return@read false
                val events = runCatching { json.decodeFromString<ChatChunkDto>(data).toEvents() }
                    .onFailure { parseFailure = true } // a malformed chunk is skipped; fatal only if nothing ever parses
                    .getOrDefault(emptyList())
                if (events.isNotEmpty()) produced = true
                events.forEach { emit(it) }
                true
            }
            if (!produced && parseFailure) throw OracleException(DomainError.Network(NetworkKind.Server), "unparseable stream")
        }
    }

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
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_PAYMENT_REQUIRED = 402
        const val HTTP_TOO_MANY = 429
        const val HTTP_SERVER_ERROR = 500
        const val MAX_ERROR_BODY = 200
    }
}
