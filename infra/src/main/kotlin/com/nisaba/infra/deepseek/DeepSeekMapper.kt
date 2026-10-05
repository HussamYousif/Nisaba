package com.nisaba.infra.deepseek

import android.util.Base64
import com.nisaba.domain.conversation.ImageDetail
import com.nisaba.domain.conversation.OracleEvent
import com.nisaba.domain.conversation.OracleRequest
import com.nisaba.domain.conversation.Turn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Provider-agnostic [OracleRequest] → DeepSeek wire format. Images go in the FINAL user message only
 * (the API rejects them elsewhere); the document text and the memory ride in the system message so they stay in the
 * cached prefix. Memory comes after the document: a new fact then leaves the (bigger, per-notebook) document cached.
 */
fun OracleRequest.toWire(encode: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) }): ChatRequestDto {
    val systemText = buildString {
        append(system)
        documentContext?.let { append("\n\n# Document\n").append(it) }
        memoryContext?.let { append("\n\n# Memory\n").append(it) }
    }
    val messages = buildList {
        add(MessageDto("system", JsonPrimitive(systemText)))
        for (t in history) when (t) {
            is Turn.User -> add(MessageDto("user", JsonPrimitive(t.text)))
            is Turn.Assistant -> add(MessageDto("assistant", JsonPrimitive(t.text)))
        }
        add(MessageDto("user", lastUserContent(encode)))
    }
    return ChatRequestDto(
        model = options.model, messages = messages, temperature = options.temperature, maxTokens = options.maxTokens,
        thinking = ThinkingDto(if (options.thinking) "enabled" else "disabled"),
        reasoningEffort = if (options.thinking) "high" else null,
    )
}

private fun OracleRequest.lastUserContent(encode: (ByteArray) -> String): JsonElement = buildJsonArray {
    add(buildJsonObject { put("type", "text"); put("text", instruction) })
    for (img in images) add(
        buildJsonObject {
            put("type", "image_url")
            put(
                "image_url",
                buildJsonObject {
                    put("url", "data:image/jpeg;base64,${encode(img.jpeg)}")
                    put("detail", if (img.detail == ImageDetail.High) "high" else "auto")
                },
            )
        },
    )
}

fun ChatChunkDto.toEvents(): List<OracleEvent> = buildList {
    choices.firstOrNull()?.delta?.content?.takeIf { it.isNotEmpty() }?.let { add(OracleEvent.Delta(it)) }
    usage?.let { add(OracleEvent.Usage(it.promptTokens, it.completionTokens, it.cacheHitTokens)) }
}
