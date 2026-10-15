package com.nisaba.tools

import com.nisaba.domain.conversation.ImageDetail
import com.nisaba.domain.conversation.OracleRequest
import com.nisaba.domain.conversation.Turn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/** The reply text and what it cost. */
class EvalReply(val text: String, val inputTokens: Int, val outputTokens: Int, val millis: Long)

/**
 * The same request the app sends (system text with `# Document` / `# Memory`, history, the final message with both
 * images), without streaming: the eval only needs the whole reply.
 */
class EvalOracleClient(private val key: String, private val baseUrl: String = "https://api.deepseek.com") {
    private val http = OkHttpClient.Builder().readTimeout(TIMEOUT, TimeUnit.SECONDS).callTimeout(TIMEOUT, TimeUnit.SECONDS).build()

    fun ask(r: OracleRequest, temperatureScale: Double = 1.0): EvalReply {
        val body = buildJsonObject {
            put("model", r.options.model); put("stream", false)
            put("temperature", r.options.temperature * temperatureScale); put("max_tokens", r.options.maxTokens)
            putJsonObject("thinking") { put("type", if (r.options.thinking) "enabled" else "disabled") }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    val doc = r.documentContext?.let { "\n\n# Document\n$it" }.orEmpty()
                    put("content", r.system + doc + r.memoryContext?.let { "\n\n# Memory\n$it" }.orEmpty())
                }
                for (t in r.history) addJsonObject {
                    when (t) {
                        is Turn.User -> { put("role", "user"); put("content", t.text) }
                        is Turn.Assistant -> { put("role", "assistant"); put("content", t.text) }
                    }
                }
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject { put("type", "text"); put("text", r.instruction) }
                        for (img in r.images) addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(img.jpeg))
                                put("detail", if (img.detail == ImageDetail.High) "high" else "auto")
                            }
                        }
                    }
                }
            }
        }
        val request = Request.Builder().url("$baseUrl/chat/completions").header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val t0 = System.currentTimeMillis()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "HTTP ${resp.code}: ${text.take(300)}" }
            val json = Json.parseToJsonElement(text).jsonObject
            val content = json["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
            val usage = json["usage"] as? JsonObject
            return EvalReply(
                content, usage?.get("prompt_tokens")?.jsonPrimitive?.int ?: 0, usage?.get("completion_tokens")?.jsonPrimitive?.int ?: 0,
                System.currentTimeMillis() - t0,
            )
        }
    }

    private companion object {
        const val TIMEOUT = 120L
    }
}
