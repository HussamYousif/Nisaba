package com.nisaba.domain.ink

import com.nisaba.domain.shared.IdGen
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.StrokeId

class EraseUseCase(private val store: InkStore, private val idGen: IdGen) {
    suspend operator fun invoke(state: InkEditState, mode: EraseMode, path: List<PagePoint>, radius: Float): InkEditState {
        val strokes = state.ink.strokes
        val cmd: InkCommand = when (mode) {
            EraseMode.Stroke -> {
                val hit = hitStrokes(strokes, path, radius).toSet()
                if (hit.isEmpty()) return state
                InkCommand.Remove(strokes.filter { it.id in hit })
            }
            EraseMode.Area -> {
                val removed = ArrayList<Stroke>(); val added = ArrayList<Stroke>()
                for (s in strokes) {
                    val pieces = splitByEraser(s, path, radius) { StrokeId(idGen.next()) }
                    if (pieces.size == 1 && pieces[0] === s) continue
                    removed += s; added += pieces
                }
                if (removed.isEmpty()) return state
                InkCommand.Replace(removed, added)
            }
        }
        return commit(store, state, cmd)
    }
}
