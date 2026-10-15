package com.nisaba.domain.ink

/** Swaps [removed] for [added] (moved, recoloured) as ONE undoable command; unchanged state if there is nothing to do. */
class ReplaceStrokesUseCase(private val store: InkStore) {
    suspend operator fun invoke(state: InkEditState, removed: List<Stroke>, added: List<Stroke>): InkEditState =
        if (removed.isEmpty() && added.isEmpty()) state else commit(store, state, InkCommand.Replace(removed, added))
}

/** Removes [strokes] (a lasso's delete) as ONE undoable command. */
class RemoveStrokesUseCase(private val store: InkStore) {
    suspend operator fun invoke(state: InkEditState, strokes: List<Stroke>): InkEditState =
        if (strokes.isEmpty()) state else commit(store, state, InkCommand.Remove(strokes))
}
