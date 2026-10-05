package com.nisaba.domain.ink

import com.nisaba.domain.shared.ExchangeId

/** Removes every stroke of one answer from a page as ONE undoable command (unchanged state if it has none there). */
class EraseAnswerUseCase(private val store: InkStore) {
    suspend operator fun invoke(state: InkEditState, exchange: ExchangeId): InkEditState {
        val gone = state.ink.strokes.filter { (it.origin as? StrokeOrigin.Oracle)?.exchangeId == exchange }
        return if (gone.isEmpty()) state else commit(store, state, InkCommand.Remove(gone))
    }
}
