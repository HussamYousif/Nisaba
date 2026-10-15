package com.nisaba.domain.ink

import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.StrokeId

@JvmInline value class InkColor(val argb: Int) {
    companion object {
        val BLACK = InkColor(0xFF111111.toInt())
        val LAPIS = InkColor(0xFF27407A.toInt())
    }
}

/** Parallel primitive arrays (no per-point boxing). [ts] = milliseconds since stroke start. */
class InkPoints(val xs: FloatArray, val ys: FloatArray, val ps: FloatArray, val ts: IntArray) {
    init {
        require(xs.size == ys.size && xs.size == ps.size && xs.size == ts.size) { "InkPoints arrays differ in size" }
    }

    val size: Int get() = xs.size

    fun bounds(): PageRect {
        if (size == 0) return PageRect(0f, 0f, 0f, 0f)
        var l = xs[0]; var r = xs[0]; var t = ys[0]; var b = ys[0]
        for (i in 1 until size) {
            if (xs[i] < l) l = xs[i]
            if (xs[i] > r) r = xs[i]
            if (ys[i] < t) t = ys[i]
            if (ys[i] > b) b = ys[i]
        }
        return PageRect(l, t, r, b)
    }

    fun slice(from: Int, toExclusive: Int) = InkPoints(
        xs.copyOfRange(from, toExclusive), ys.copyOfRange(from, toExclusive),
        ps.copyOfRange(from, toExclusive), ts.copyOfRange(from, toExclusive),
    )

    override fun equals(other: Any?) = other is InkPoints &&
        xs.contentEquals(other.xs) && ys.contentEquals(other.ys) && ps.contentEquals(other.ps) && ts.contentEquals(other.ts)

    override fun hashCode() = xs.contentHashCode() * 31 + ys.contentHashCode()

    companion object {
        fun of(points: List<FloatArray>): InkPoints = InkPoints(
            FloatArray(points.size) { points[it][0] }, FloatArray(points.size) { points[it][1] },
            FloatArray(points.size) { points.getOrNull(it)?.getOrNull(2) ?: 0.5f },
            IntArray(points.size) { points[it].getOrNull(3)?.toInt() ?: (it * 8) },
        )
    }
}

sealed interface StrokeOrigin {
    data object User : StrokeOrigin
    data class Oracle(val exchangeId: ExchangeId) : StrokeOrigin
}

data class Stroke(
    val id: StrokeId,
    val points: InkPoints,
    val width: Float,
    val color: InkColor,
    val origin: StrokeOrigin,
) {
    /** Bounds of the centre line (not inflated by [width]). */
    val bounds: PageRect = points.bounds()
}

/**
 * The ink of one page. [setAside] = some saved ink could not be read: its file was moved aside (kept, never overwritten)
 * and the page holds what could be read.
 */
data class PageInk(val pageId: PageId, val strokes: List<Stroke>, val setAside: Boolean = false)

enum class EraseMode { Stroke, Area }

sealed interface InkCommand {
    data class Add(val strokes: List<Stroke>) : InkCommand
    data class Remove(val strokes: List<Stroke>) : InkCommand
    data class Replace(val removed: List<Stroke>, val added: List<Stroke>) : InkCommand
}

data class InkHistory(val undo: List<InkCommand> = emptyList(), val redo: List<InkCommand> = emptyList())

/** What an open page holds in memory: the ink and its undo history. */
data class InkEditState(val ink: PageInk, val history: InkHistory = InkHistory()) {
    val canUndo: Boolean get() = history.undo.isNotEmpty()
    val canRedo: Boolean get() = history.redo.isNotEmpty()
}
