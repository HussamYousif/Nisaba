package com.nisaba.scribe.layout

import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.hand.Glyph
import java.util.BitSet

/** Which pen a stroke is written with: the answer's own ink, or a highlighter tone inside code. */
enum class InkTone { Plain, Keyword, Literal, Comment }

/** Where an answer may be written. All values are page units (px of the Air 5C panel in portrait). */
data class LayoutRegion(
    val pageSize: PageSize,
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val xHeight: Float,
    /** Where writing starts on every page AFTER the first (the first starts at [top], under the question). */
    val pageTop: Float = MARGIN_TOP,
) {
    val lineHeight: Float get() = xHeight * LINE_HEIGHT_FACTOR
    val width: Float get() = right - left

    companion object {
        const val LINE_HEIGHT_FACTOR = 2.6f
        const val MARGIN = 120f
        const val MARGIN_TOP = 140f
        const val MARGIN_BOTTOM = 160f

        /** Starting under an utterance: [anchorX]/[anchorY] from `answerAnchor`, hand size from the user's writing. */
        fun below(page: PageSize, anchorX: Float, anchorY: Float, xHeight: Float) =
            if (page.isEndless) {
                LayoutRegion(page, anchorX, anchorX + ENDLESS_ANSWER_WIDTH, anchorY, anchorY + ENDLESS_ANSWER_HEIGHT, xHeight)
            } else {
                LayoutRegion(page, anchorX, page.w - MARGIN, anchorY, page.h - MARGIN_BOTTOM, xHeight)
            }

        /** On an endless canvas an answer is a column this wide, as tall as it needs (it never spills to another page). */
        const val ENDLESS_ANSWER_WIDTH = 1400f
        const val ENDLESS_ANSWER_HEIGHT = 8000f
    }
}

/** Coarse grid of "something is already here" cells over one page (existing ink, printed text). */
class Occupancy(
    val cell: Float,
    val cols: Int,
    val rows: Int,
    private val bits: BitSet,
    /** Page position of the grid's top-left cell: an endless canvas only tracks a window around the answer. */
    val originX: Float = 0f,
    val originY: Float = 0f,
) {
    /** An independent copy, so per-answer marks (fresh ink) never leak into a cached page picture. */
    fun copy() = Occupancy(cell, cols, rows, bits.clone() as BitSet, originX, originY)

    /** Marks what [r] covers inside the grid; anything wholly outside the window is ignored. */
    fun mark(r: PageRect) {
        if (!overlaps(r)) return
        for (row in rowRange(r.top - originY, r.bottom - originY)) for (col in colRange(r.left - originX, r.right - originX)) {
            bits.set(row * cols + col)
        }
    }

    fun isFree(r: PageRect): Boolean {
        if (!overlaps(r)) return true
        for (row in rowRange(r.top - originY, r.bottom - originY)) for (col in colRange(r.left - originX, r.right - originX)) {
            if (bits[row * cols + col]) return false
        }
        return true
    }

    private fun overlaps(r: PageRect) =
        r.right >= originX && r.left <= originX + cols * cell && r.bottom >= originY && r.top <= originY + rows * cell

    private fun rowRange(a: Float, b: Float) = (a / cell).toInt().coerceIn(0, rows - 1)..(b / cell).toInt().coerceIn(0, rows - 1)
    private fun colRange(a: Float, b: Float) = (a / cell).toInt().coerceIn(0, cols - 1)..(b / cell).toInt().coerceIn(0, cols - 1)

    companion object {
        const val CELL = 16f

        fun empty(size: PageSize, cell: Float = CELL) =
            Occupancy(cell, (size.w / cell).toInt() + 1, (size.h / cell).toInt() + 1, BitSet())

        /** A grid over just [window] of a page (for endless canvases). */
        fun window(window: PageRect, cell: Float = CELL) =
            Occupancy(cell, (window.width / cell).toInt() + 1, (window.height / cell).toInt() + 1, BitSet(), window.left, window.top)

        fun of(size: PageSize, rects: List<PageRect>, pad: Float = 12f, cell: Float = CELL) =
            empty(size, cell).also { o -> rects.forEach { o.mark(PageRect(it.left - pad, it.top - pad, it.right + pad, it.bottom + pad)) } }
    }
}

class PlacedChar(val ch: Char, val glyph: Glyph, val dx: Float, val scale: Float)

/** A word with its glyph advances resolved; [x]/[baseline] are page coordinates of the pen start. */
class LaidWord(
    val page: Int,
    val x: Float,
    val baseline: Float,
    val scale: Float,
    val chars: List<PlacedChar>,
    val width: Float,
    val bold: Boolean,
    val line: Int,
    val index: Int,
    /** Where the word's line starts: the baseline's slope runs from there, so a line drifts as one. */
    val lineX: Float = x,
    /** False for code: letters stay apart even in a joined hand. */
    val joinable: Boolean = true,
    val tone: InkTone = InkTone.Plain,
)

/** A pen path that is not letters: a ruled line, a table grid line, a diagram stroke. Page coordinates. */
class LaidPath(val page: Int, val xs: FloatArray, val ys: FloatArray) {
    companion object {
        fun line(page: Int, x0: Float, y0: Float, x1: Float, y1: Float) = LaidPath(page, floatArrayOf(x0, x1), floatArrayOf(y0, y1))
    }
}

/**
 * Everything one answer block puts on the page. [pathsFirst] = draw the paths before the words (a table's grid, a
 * diagram's shapes come before the text that fills them; a heading's underline comes after its words).
 */
class LaidBlock(val words: List<LaidWord>, val paths: List<LaidPath>, val bounds: PageRect?, val pathsFirst: Boolean = false)

/** Where the next line goes. [lineTop] is the top of the next line box on [page] (0 = the page the answer started on). */
data class LayoutCursor(val page: Int, val lineTop: Float, val lineCount: Int = 0, val wordCount: Int = 0)
