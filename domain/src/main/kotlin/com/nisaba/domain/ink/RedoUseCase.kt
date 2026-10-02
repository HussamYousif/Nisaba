package com.nisaba.domain.ink

class RedoUseCase(private val store: InkStore) {
    suspend operator fun invoke(state: InkEditState): InkEditState {
        val (history, cmd) = redo(state.history) ?: return state
        val ink = state.ink.copy(strokes = applyCommand(state.ink.strokes, cmd))
        store.save(ink.pageId, ink.strokes)
        return InkEditState(ink, history)
    }
}
