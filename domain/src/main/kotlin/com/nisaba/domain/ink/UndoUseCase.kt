package com.nisaba.domain.ink

class UndoUseCase(private val store: InkStore) {
    suspend operator fun invoke(state: InkEditState): InkEditState {
        val (history, cmd) = undo(state.history) ?: return state
        val ink = state.ink.copy(strokes = applyCommand(state.ink.strokes, cmd))
        store.save(ink.pageId, ink.strokes)
        return InkEditState(ink, history)
    }
}
