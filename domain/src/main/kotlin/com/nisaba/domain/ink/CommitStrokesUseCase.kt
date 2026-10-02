package com.nisaba.domain.ink

/** Commits several strokes (an answer, or part of one) as ONE undoable command. */
class CommitStrokesUseCase(private val store: InkStore) {
    suspend operator fun invoke(state: InkEditState, strokes: List<Stroke>): InkEditState =
        if (strokes.isEmpty()) state else commit(store, state, InkCommand.Add(strokes))
}
