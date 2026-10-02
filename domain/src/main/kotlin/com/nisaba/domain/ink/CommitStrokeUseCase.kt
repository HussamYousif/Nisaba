package com.nisaba.domain.ink

class CommitStrokeUseCase(private val store: InkStore) {
    suspend operator fun invoke(state: InkEditState, stroke: Stroke): InkEditState = commit(store, state, InkCommand.Add(listOf(stroke)))
}

internal suspend fun commit(store: InkStore, state: InkEditState, cmd: InkCommand): InkEditState {
    val next = state.ink.copy(strokes = applyCommand(state.ink.strokes, cmd))
    store.save(next.pageId, next.strokes)
    return InkEditState(next, push(state.history, cmd))
}
