package com.nisaba.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * DeepSeek client (docs/07 §1–3): OpenAI-compatible /chat/completions with
 * vision input, structured JSON output, and proper coroutine cancellation —
 * cancelling the calling coroutine aborts the network call (F3.2).
 *
 * One shared OkHttpClient (audit P1-10): a fresh client per instance spawned
 * a Dispatcher executor, ConnectionPool and cleanup thread that never shut
 * down. Timeouts cover the whole call, and 429/5xx retry once after 5 s as
 * docs/07 §6 promises.
 */
class DeepSeekClient(
    private val settings: AppSettings,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // response_format etc. must always be sent
    },
) {
    /** Returns the assistant's raw text content. Throws typed [ApiException]s. */
    suspend fun chat(request: ChatContract.ChatRequest): String {
        var attempt = 0
        while (true) {
            try {
                return chatOnce(request)
            } catch (e: ApiException) {
                // One retry after 5 s on transient server-side statuses
                // (docs/07 §6). Cancellation during the delay propagates.
                if (attempt == 0 && e.code in RETRYABLE_CODES) {
                    attempt = 1
                    delay(RETRY_DELAY_MS)
                } else {
                    throw e
                }
            }
        }
    }

    private suspend fun chatOnce(request: ChatContract.ChatRequest): String =
        suspendCancellableCoroutine { cont ->
            val body = json.encodeToString(ChatContract.ChatRequest.serializer(), request)
                .toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url(settings.chatCompletionsUrl())
                .header("Authorization", "Bearer ${settings.effectiveApiKey()}")
                .post(body)
                .build()
            val call = CLIENT.newCall(httpRequest)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(mapNetworkError(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (cont.isCancelled) return
                        val bodyText = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            cont.resumeWithException(ApiException(resp.code, bodyText.take(300)))
                            return
                        }
                        try {
                            val parsed = json.decodeFromString(ChatContract.ChatResponse.serializer(), bodyText)
                            val error = parsed.error
                            if (error != null) {
                                val msg = error["message"]?.toString().orEmpty()
                                cont.resumeWithException(ApiException(resp.code, msg))
                                return
                            }
                            val text = ChatContract.assistantText(parsed)
                            if (text == null) {
                                cont.resumeWithException(ApiException(resp.code, "empty assistant message"))
                            } else {
                                cont.resume(text)
                            }
                        } catch (e: Exception) {
                            cont.resumeWithException(ApiException(resp.code, "unparseable response: ${e.message}"))
                        }
                    }
                }
            })
        }

    private fun mapNetworkError(e: IOException): Exception = when (e) {
        is java.net.SocketTimeoutException -> NetworkException("timeout", e)
        else -> NetworkException(e.message ?: "network error", e)
    }

    class ApiException(val code: Int, message: String) : Exception(message)
    class NetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        private val CLIENT = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // A multi-MB base64 body over weak Wi-Fi needs more than the 10 s
            // default; callTimeout bounds the whole attempt (audit P1-10).
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()

        private const val RETRY_DELAY_MS = 5_000L
        private val RETRYABLE_CODES = setOf(429, 500, 502, 503, 504)
    }
}
