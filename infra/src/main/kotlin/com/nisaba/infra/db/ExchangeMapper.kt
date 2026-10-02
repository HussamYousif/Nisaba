package com.nisaba.infra.db

import com.nisaba.domain.conversation.Exchange
import com.nisaba.domain.conversation.ExchangeStatus
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId

fun Exchange.toDto() = ExchangeDto(
    id.value, notebookId.value, pageId.value, heard, answerMarkup, status.ordinal, createdAtMillis,
    model, promptVersion, latencyMillis, inputTokens, outputTokens,
)

fun ExchangeDto.toModel() = Exchange(
    ExchangeId(id), NotebookId(notebookId), PageId(pageId), heard, answerMarkup,
    ExchangeStatus.entries.getOrElse(status) { ExchangeStatus.Failed }, createdAt, model, promptVersion, latencyMillis,
    inputTokens, outputTokens,
)
