package com.nisaba.domain.conversation

import com.nisaba.domain.shared.ExchangeId

/** The answer was erased from the page: the exchange stays on record but leaves the conversation's history. */
class MarkAnswerErasedUseCase(private val exchanges: ExchangeStore) {
    suspend operator fun invoke(id: ExchangeId) {
        val e = exchanges.get(id) ?: return
        if (e.status != ExchangeStatus.Erased) exchanges.append(e.copy(status = ExchangeStatus.Erased))
    }
}
