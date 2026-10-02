package com.nisaba.scribe.layout

import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.DiagramLabel
import com.nisaba.domain.answer.parseDiagram
import com.nisaba.domain.shared.PageRect
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val TARGET_WIDTH = 900f
private const val MAX_HEIGHT = 1100f
private const val LABEL_SCALE = 0.8f
private const val OVERSHOOT_POINTS = 2      // a closed outline runs a little past where it started, like a real hand
private const val LABEL_CLEARANCE = 0.45f   // keep labels this many x-heights away from strokes
private const val PAD = 0.6f

private fun pointNearPath(px: Float, py: Float, xs: FloatArray, ys: FloatArray, dist: Float): Boolean {
    for (i in 0 until xs.size - 1) {
        val dx = xs[i + 1] - xs[i]; val dy = ys[i + 1] - ys[i]
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - xs[i]) * dx + (py - ys[i]) * dy) / len2).coerceIn(0f, 1f)
        if (hypot(px - (xs[i] + t * dx), py - (ys[i] + t * dy)) < dist) return true
    }
    return false
}

/** True if the label box (sampled along its edges and middle) comes too close to any stroke. */
private fun touchesShapes(x: Float, y: Float, w: Float, h: Float, paths: List<LaidPath>, dist: Float): Boolean {
    val probes = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    for (px in probes) for (py in listOf(0f, 0.5f, 1f)) {
        if (paths.any { pointNearPath(x + w * px, y + h * py, it.xs, it.ys, dist) }) return true
    }
    return false
}

/**
 * A diagram: the shapes come first (in the order the model drew them), then the labels. The drawing is fitted to the
 * page width and the remaining page height, centred, and labels are nudged off the strokes.
 */
internal fun layoutDiagram(d: Block.Diagram, cursor: LayoutCursor, ctx: Ctx): Pair<LaidBlock, LayoutCursor> {
    val drawing = parseDiagram(d.svg) ?: return fallback(d, cursor, ctx)
    val xh = ctx.region.xHeight
    val all = drawing.shapes
    val minX = all.minOf { it.xs.min() }; val maxX = all.maxOf { it.xs.max() }
    val minY = all.minOf { it.ys.min() }; val maxY = all.maxOf { it.ys.max() }
    val w = max(maxX - minX, 1f); val h = max(maxY - minY, 1f)
    val scale = min(min(TARGET_WIDTH / w, MAX_HEIGHT / h), TARGET_WIDTH / drawing.viewW).coerceAtLeast(0.05f)
    val height = h * scale + 2 * PAD * xh
    val (page, top) = findBox(cursor.page, cursor.lineTop, ctx.region.left, height, ctx)
    val left = ctx.region.left + (ctx.region.width - w * scale) / 2f
    fun mx(x: Float) = left + (x - minX) * scale
    fun my(y: Float) = top + PAD * xh + (y - minY) * scale

    val paths = all.map { s ->
        val n = s.xs.size
        val extra = if (s.closed && n > OVERSHOOT_POINTS + 1) OVERSHOOT_POINTS else 0
        // the overshoot re-walks the first points of the outline (index n maps back to 1, n+1 to 2, …)
        fun src(i: Int) = if (i < n) i else i - n + 1
        LaidPath(page, FloatArray(n + extra) { mx(s.xs[src(it)]) }, FloatArray(n + extra) { my(s.ys[src(it)]) })
    }
    val words = labels(d.labels, ::mx, ::my, paths, page, xh, ctx, cursor)
    val bounds = PageRect(left, top, left + w * scale, top + height)
    return LaidBlock(words, paths, bounds, pathsFirst = true) to
        LayoutCursor(page, top + height + ctx.region.lineHeight * 0.4f, cursor.lineCount + 1 + words.size, cursor.wordCount + words.size)
}

private const val NUDGE_RINGS = 3

private fun overlaps(a: FloatArray, b: FloatArray, pad: Float) =
    a[0] < b[2] + pad && b[0] < a[2] + pad && a[1] < b[3] + pad && b[1] < a[3] + pad

/** Candidate offsets around the requested spot: the spot itself, then rings of 8 directions farther and farther out. */
private fun candidateOffsets(step: Float): List<Pair<Float, Float>> = buildList {
    add(0f to 0f)
    for (ring in 1..NUDGE_RINGS) {
        val d = step * ring
        for ((ux, uy) in listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0, -1 to -1, 1 to -1, -1 to 1, 1 to 1)) add(ux * d to uy * d)
    }
}

private fun labels(
    labels: List<DiagramLabel>, mx: (Float) -> Float, my: (Float) -> Float, paths: List<LaidPath>, page: Int, xh: Float,
    ctx: Ctx, cursor: LayoutCursor,
): List<LaidWord> {
    val scale = xh * LABEL_SCALE
    val placed = ArrayList<FloatArray>()
    return labels.mapIndexed { i, l ->
        val (chars, width) = measure(normalizeTypography(l.text).replace('\n', ' '), ctx.set, scale)
        val height = scale * 1.4f
        val x0 = mx(l.x.toFloat()) - width / 2f; val y0 = my(l.y.toFloat()) - height / 2f
        val free = candidateOffsets(xh * 0.9f).firstOrNull { (dx, dy) ->
            !touchesShapes(x0 + dx, y0 + dy, width, height, paths, xh * LABEL_CLEARANCE) &&
                placed.none { overlaps(floatArrayOf(x0 + dx, y0 + dy, x0 + dx + width, y0 + dy + height), it, xh * 0.3f) }
        } ?: (0f to 0f)
        val x = x0 + free.first; val y = y0 + free.second
        placed += floatArrayOf(x, y, x + width, y + height)
        LaidWord(page, x, y + height * 0.8f, scale, chars, width, false, cursor.lineCount + i, cursor.wordCount + i)
    }
}

/** A drawing that cannot be parsed still says something: its labels, or that a picture belonged here. */
private fun fallback(d: Block.Diagram, cursor: LayoutCursor, ctx: Ctx): Pair<LaidBlock, LayoutCursor> {
    val caption = d.labels.joinToString("  ") { it.text }.ifEmpty { "(a drawing)" }
    return layoutBlock(Block.Paragraph(listOf(com.nisaba.domain.answer.Inline.Text(caption))), cursor, ctx.region, ctx.occ, ctx.set)
}

