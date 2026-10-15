package com.nisaba.infra.db

import com.nisaba.domain.conversation.Exchange
import com.nisaba.domain.conversation.ExchangeStatus
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.usage.ExchangeUsage

fun Exchange.toDto() = ExchangeDto(
    id.value, notebookId.value, pageId.value, heard, answerMarkup, status.ordinal, createdAtMillis,
    model, promptVersion, latencyMillis, inputTokens, outputTokens, cachedTokens,
    focus?.left, focus?.top, focus?.right, focus?.bottom, personaId, firstTokenMillis, firstBlockMillis,
)

fun ExchangeDto.toModel() = Exchange(
    ExchangeId(id), NotebookId(notebookId), PageId(pageId), heard, answerMarkup,
    ExchangeStatus.entries.getOrElse(status) { ExchangeStatus.Failed }, createdAt, model, promptVersion, latencyMillis,
    inputTokens, outputTokens, cachedTokens, focusRect(), persona, firstTokenMillis, firstBlockMillis,
)

private fun ExchangeDto.focusRect(): PageRect? {
    val l = focusLeft ?: return null; val t = focusTop ?: return null
    val r = focusRight ?: return null; val b = focusBottom ?: return null
    return PageRect(l, t, r, b)
}

fun UsageRowDto.toModel() = ExchangeUsage(
    model, createdAt, inputTokens, cachedTokens, outputTokens,
    silent = status == ExchangeStatus.Silent.ordinal, firstInkMillis = firstBlockMillis,
)
