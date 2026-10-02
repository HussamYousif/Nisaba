package com.nisaba.infra.deepseek

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class ChatRequestDto(
    val model: String,
    val messages: List<MessageDto>,
    val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: StreamOptionsDto = StreamOptionsDto(),
    val temperature: Double,
    @SerialName("max_tokens") val maxTokens: Int,
    val thinking: ThinkingDto,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable class StreamOptionsDto(@SerialName("include_usage") val includeUsage: Boolean = true)

@Serializable class ThinkingDto(val type: String)

/** [content] is a plain string, or an array of text/image parts for the final user message. */
@Serializable class MessageDto(val role: String, val content: JsonElement)

@Serializable class ChatChunkDto(val choices: List<ChoiceDto> = emptyList(), val usage: UsageDto? = null)

@Serializable class ChoiceDto(val delta: DeltaDto? = null)

@Serializable class DeltaDto(val content: String? = null)

@Serializable
class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("prompt_cache_hit_tokens") val cacheHitTokens: Int = 0,
)
