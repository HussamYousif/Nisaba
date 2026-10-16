package com.nisaba.domain.ink

import com.nisaba.domain.shared.IdGen
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.StrokeId

/**
 * Erasing along a path. Hit-testing a long scrub over a dense page is real work: it runs on [compute] when given (never
 * on the thread that draws; edits are serialized by the page's session, so the state it reads cannot change meanwhile).
 */
class EraseUseCase(
    private val store: InkStore,
    private val idGen: IdGen,
    private val compute: kotlinx.coroutines.CoroutineDispatcher? = null,
) {
    suspend operator fun invoke(state: InkEditState, mode: EraseMode, path: List<PagePoint>, radius: Float): InkEditState {
        val cmd = compute?.let { kotlinx.coroutines.withContext(it) { plan(state, mode, path, radius) } }
            ?: plan(state, mode, path, radius)
            ?: return state
        return commit(store, state, cmd)
    }

    private fun plan(state: InkEditState, mode: EraseMode, path: List<PagePoint>, radius: Float): InkCommand? {
        val strokes = state.ink.strokes
        return when (mode) {
            EraseMode.Stroke -> {
                val hit = hitStrokes(strokes, path, radius).toSet()
                if (hit.isEmpty()) return null
                InkCommand.Remove(strokes.filter { it.id in hit })
            }
            EraseMode.Area -> {
                val removed = ArrayList<Stroke>(); val added = ArrayList<Stroke>()
                val pathBounds = boundsOfPath(path)
                for (s in strokes) {
                    val pieces = splitByEraser(s, path, radius, pathBounds) { StrokeId(idGen.next()) }
                    if (pieces.size == 1 && pieces[0] === s) continue
                    removed += s; added += pieces
                }
                if (removed.isEmpty()) return null
                InkCommand.Replace(removed, added)
            }
        }
    }
}
