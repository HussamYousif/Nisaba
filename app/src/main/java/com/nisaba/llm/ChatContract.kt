package com.nisaba.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenAI-compatible chat completion request/response shapes
 * (docs/07 §1–3). Only the fields Nisaba uses.
 *
 * NOTE: `content` is a JsonElement on purpose — providers may return either a
 * plain string (text models) or an array of parts (multimodal).
 */
object ChatContract {

    @Serializable
    data class Message(
        val role: String,
        val content: JsonElement? = null,
    )

    @Serializable
    data class ContentPart(
        val type: String,
        val text: String? = null,
        val image_url: JsonObject? = null, // { "url": "data:image/png;base64,…" }
    )

    @Serializable
    data class ChatRequest(
        val model: String,
        val temperature: Float = 0.7f,
        val max_tokens: Int = 4096,
        val response_format: JsonObject = JsonObject(
            mapOf("type" to JsonPrimitive("json_object"))
        ),
        val messages: List<Message> = emptyList(),
    )

    @Serializable
    data class ChatResponse(
        val choices: List<Choice> = emptyList(),
        val error: JsonObject? = null,
    ) {
        @Serializable
        data class Choice(val message: Message? = null)
    }

    /** Plain-text user message (connection tests, repair prompts). */
    fun textMessage(role: String, text: String): Message =
        Message(role, JsonPrimitive(text))

    /** Vision user message: instruction text + page image data-URI. */
    fun visionMessage(role: String, text: String, imageDataUri: String): Message =
        Message(
            role,
            JsonArray(
                listOf(
                    JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))),
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("image_url"),
                            "image_url" to JsonObject(mapOf("url" to JsonPrimitive(imageDataUri))),
                        )
                    ),
                )
            ),
        )

    /** Extracts the assistant's raw content (string or joined text parts). */
    fun assistantText(response: ChatResponse): String? =
        response.choices.firstOrNull()?.message?.content?.let { content ->
            when (content) {
                is JsonPrimitive -> content.contentOrNull
                is JsonArray -> content.mapNotNull { el ->
                    (el as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                }.joinToString("")
                else -> null
            }
        }?.takeIf { it.isNotBlank() }
}
