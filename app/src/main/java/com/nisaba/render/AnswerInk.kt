package com.nisaba.render

import android.content.Context
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import com.nisaba.magic.Fonts
import com.nisaba.magic.MagicTheme
import com.nisaba.notebook.model.AnswerGroup

/**
 * Lays an answer group out as PAGE-SPACE ink (docs/08 §1): the scribe's reply
 * is drawn on the canvas itself, below the last content — no separate UI
 * section, no scrolling to find it. Items carry reveal ticks so the ink can
 * write itself in, in order.
 */
object AnswerInk {

    class Layout(val items: List<Item>, val heightPx: Float) {
        val itemCount: Int get() = items.size
        val totalTicks: Int get() = items.sumOf { it.revealTicks }

        sealed class Item(val revealTicks: Int) {
            class Line(val paint: Paint, val text: String, val x: Float, val baselineY: Float) : Item(1)
            class Path(val path: android.graphics.Path, val paint: Paint, val length: Float, val fill: Boolean) : Item(6)
            class Rule(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val paint: Paint) : Item(1)
        }
    }

    // Sizes are page-space px (≈ screen px at the Boox's fit-width scale 1).
    private const val MARGIN = 70f
    private const val BODY_SIZE = 46f
    private const val BODY_LINE = 70f
    private const val HEADING_SIZE = 64f
    private const val FLOURISH_SIZE = 42f
    private const val TABLE_ROW = 58f
    private const val CELL_SIZE = 38f
    private const val TABLE_HEADER_SIZE = 46f
    private const val DRAWING_MAX_WIDTH = 1300f
    private const val BLOCK_GAP = 44f

    /** Lays the group out starting at [startY] (page space). */
    fun layout(group: AnswerGroup, pageWidth: Float, startY: Float, context: Context): Layout {
        val items = ArrayList<Layout.Item>()
        val usable = pageWidth - 2 * MARGIN
        var y = startY

        fun paint(size: Float, typeface: android.graphics.Typeface, color: Int): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                textSize = size
                this.color = color
            }

        fun addText(
            text: String,
            size: Float,
            lineH: Float,
            typeface: android.graphics.Typeface,
            color: Int = MagicTheme.INK,
            indent: Float = 0f,
            gapBefore: Float = 0f,
        ) {
            y += gapBefore
            val p = paint(size, typeface, color)
            val fm = p.fontMetrics
            var line = StringBuilder()
            var width = 0f
            val spaceW = p.measureText(" ")
            val maxW = usable - indent
            for (word in text.split(' ')) {
                val ww = if (line.isEmpty()) p.measureText(word) else spaceW + p.measureText(word)
                if (width + ww > maxW && line.isNotEmpty()) {
                    items.add(Layout.Item.Line(p, line.toString(), MARGIN + indent, y - fm.top))
                    y += lineH
                    line = StringBuilder(word)
                    width = p.measureText(word)
                } else {
                    if (line.isNotEmpty()) line.append(' ')
                    line.append(word)
                    width += ww
                }
            }
            if (line.isNotEmpty()) {
                items.add(Layout.Item.Line(p, line.toString(), MARGIN + indent, y - fm.top))
                y += lineH
            }
        }

        fun addRule(yAt: Float, widthPx: Float = 2f, color: Int = MagicTheme.INK_SOFT) {
            items.add(Layout.Item.Rule(MARGIN, yAt, pageWidth - MARGIN, yAt, paint(widthPx, Fonts.kalam, color)))
        }

        // ── ✦ divider ──
        y += 18
        addRule(y)
        y += 14
        addText("✦", FLOURISH_SIZE, FLOURISH_SIZE + 8f, Fonts.greatVibes, MagicTheme.ACCENT_RED)
        y += 6

        for (block in group.blocks) {
            when (block.type) {
                "text" -> {
                    val chunks = block.markdown.orEmpty()
                        .replace("\r\n", "\n")
                        .split(Regex("\n\\s*\n"))
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    for (chunk in chunks) {
                        for (line in chunk.split('\n')) {
                            when {
                                line.startsWith("## ") -> addText(
                                    plain(line.removePrefix("## ").trim()),
                                    HEADING_SIZE, HEADING_SIZE * 1.4f, Fonts.greatVibes,
                                    gapBefore = BLOCK_GAP * 0.5f,
                                )
                                line.startsWith("- ") -> addText(
                                    "•  " + plain(line.removePrefix("- ").trim()),
                                    BODY_SIZE, BODY_LINE, Fonts.dancingScript, indent = 46f,
                                )
                                else -> addText(plain(line.trimEnd()), BODY_SIZE, BODY_LINE, Fonts.dancingScript)
                            }
                        }
                        y += BLOCK_GAP * 0.6f
                    }
                }
                "drawing" -> {
                    val parsed = SvgInkRenderer.parse(block.svg.orEmpty())
                    if (!parsed.isNullOrEmpty()) {
                        y += 18
                        val target = minOf(DRAWING_MAX_WIDTH, usable)
                        val s = target / SvgInkRenderer.VIEWBOX
                        val dx = MARGIN + (usable - target) / 2f
                        for (di in parsed) {
                            val path = android.graphics.Path(di.path)
                            path.transform(Matrix().apply {
                                setScale(s, s)
                                postTranslate(dx, y)
                            })
                            val stroke = Paint(di.paint).apply {
                                strokeWidth = di.paint.strokeWidth * s
                            }
                            items.add(Layout.Item.Path(path, stroke, pathLength(path), di.fill))
                        }
                        y += target + 36
                    }
                }
                "table" -> y = addTable(items, block, pageWidth, y, usable, ::paint)
            }
        }

        // ── end flourish ──
        y += 10
        addRule(y, widthPx = 2f, color = MagicTheme.INK_SOFT)
        y += 14
        addText("✧", FLOURISH_SIZE, FLOURISH_SIZE + 8f, Fonts.greatVibes, MagicTheme.ACCENT_RED)
        y += 16

        return Layout(items, y - startY)
    }

    // ------------------------------------------------------------ pieces

    private fun addTable(
        items: MutableList<Layout.Item>,
        block: com.nisaba.notebook.model.AnswerBlock,
        pageWidth: Float,
        startY: Float,
        usable: Float,
        paint: (Float, android.graphics.Typeface, Int) -> Paint,
    ): Float {
        var y = startY
        val headers = block.headers.orEmpty()
        val rows = block.rows.orEmpty()
        val n = maxOf(headers.size, rows.map { it.size }.maxOrNull() ?: 0).coerceAtLeast(1)

        val headerPaint = paint(TABLE_HEADER_SIZE, Fonts.greatVibes, MagicTheme.INK)
        val cellPaint = paint(CELL_SIZE, Fonts.kalam, MagicTheme.INK)
        val headerFm = headerPaint.fontMetrics

        block.title?.takeIf { it.isNotBlank() }?.let {
            y += 10
            val p = paint(HEADING_SIZE * 0.8f, Fonts.greatVibes, MagicTheme.INK)
            items.add(Layout.Item.Line(p, it, 70f, y - p.fontMetrics.top))
            y += HEADING_SIZE * 1.2f
        }

        // Column widths by content, fitted to the usable width.
        val colW = FloatArray(n) { 140f }
        for (i in 0 until n) {
            val h = headers.getOrNull(i).orEmpty()
            var w = headerPaint.measureText(h)
            for (row in rows) {
                w = maxOf(w, cellPaint.measureText(row.getOrNull(i).orEmpty()))
            }
            colW[i] = w + 48f
        }
        val total = colW.sum()
        if (total > usable) {
            val k = usable / total
            for (i in 0 until n) colW[i] *= k
        }
        val colX = FloatArray(n + 1)
        colX[0] = 70f
        for (i in 0 until n) colX[i + 1] = colX[i] + colW[i]

        val headerTop = y
        for (i in 0 until n) {
            items.add(Layout.Item.Line(headerPaint, headers.getOrNull(i).orEmpty(), colX[i] + 12f, y - headerFm.top))
        }
        y += TABLE_HEADER_SIZE * 1.3f
        items.add(Layout.Item.Rule(colX[0], y, colX[n], y, paint(3f, Fonts.kalam, MagicTheme.INK)))
        items.add(Layout.Item.Rule(colX[0], y + 5f, colX[n], y + 5f, paint(2f, Fonts.kalam, MagicTheme.INK_SOFT)))
        y += 22

        val cellFm = cellPaint.fontMetrics
        for ((r, cells) in rows.withIndex()) {
            val baseline = y - cellFm.top
            for (i in 0 until n) {
                items.add(Layout.Item.Line(cellPaint, cells.getOrNull(i).orEmpty(), colX[i] + 12f, baseline))
            }
            y += TABLE_ROW
            if ((r + 1) % 4 == 0 && r != rows.lastIndex) {
                items.add(Layout.Item.Rule(colX[0], y, colX[n], y, paint(2f, Fonts.kalam, MagicTheme.INK_SOFT)))
                y += 6
            }
        }
        // Vertical rules between columns.
        for (i in 1 until n) {
            items.add(Layout.Item.Rule(colX[i], headerTop, colX[i], y, paint(2f, Fonts.kalam, MagicTheme.INK_SOFT)))
        }
        return y + 14
    }

    /** Strips the markdown subset markers for canvas ink. */
    fun plain(markdown: String): String =
        markdown.replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
            .replace(Regex("""\*([^*]+)\*"""), "$1")
            .replace("`", "")

    private fun pathLength(path: android.graphics.Path): Float {
        val m = PathMeasure(path, false)
        var total = m.length
        while (m.nextContour()) total += m.length
        return total
    }
}
