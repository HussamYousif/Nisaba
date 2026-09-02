package com.nisaba.render

import android.content.Context
import android.graphics.Matrix
import android.graphics.Paint
import com.nisaba.hand.Scribe
import com.nisaba.magic.Fonts
import com.nisaba.magic.MagicTheme
import com.nisaba.notebook.model.AnswerGroup
import kotlin.math.abs

/**
 * Lays an answer group out as PAGE-SPACE ink (docs/08 §1): the scribe's reply
 * is drawn on the canvas itself, below the last content — no separate UI
 * section, no scrolling to find it. Items carry reveal ticks so the ink can
 * write itself in, in order.
 *
 * Text blocks are synthesized by the hand domain (com.nisaba.hand.Scribe)
 * into true pen strokes — the answer writes itself in like a stylus wrote
 * it (docs/08 §3). Drawings stay vector paths; tables keep ruled lines and
 * dense font cells; the ✦/✧ flourishes stay ornaments.
 */
object AnswerInk {

    class Layout(val items: List<Item>, val heightPx: Float) {
        val itemCount: Int get() = items.size
        val totalTicks: Int get() = items.sumOf { it.revealTicks }

        sealed class Item(val revealTicks: Int) {
            class Line(val paint: Paint, val text: String, val x: Float, val baselineY: Float) : Item(1)
            class Path(val path: android.graphics.Path, val paint: Paint, val length: Float, val fill: Boolean) : Item(6)
            class Rule(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val paint: Paint) : Item(1)

            /**
             * A pen stroke (a scribed word, its dots/crossings, or a drawn
             * mark): a dense polyline with a width per point, in page px.
             * Reveal draws the stroke up to a fraction of its arc length.
             */
            class Stroke(
                val xys: FloatArray,
                val widths: FloatArray,
                val length: Float,
                ticks: Int,
            ) : Item(ticks) {
                val pointCount: Int get() = xys.size / 2
            }
        }
    }

    // Sizes are page-space px (≈ screen px at the Boox's fit-width scale 1).
    private const val MARGIN = 70f
    private const val FLOURISH_SIZE = 42f
    private const val TABLE_ROW = 58f
    private const val CELL_SIZE = 38f
    private const val TABLE_HEADER_SIZE = 46f
    private const val DRAWING_MAX_WIDTH = 1300f
    private const val BLOCK_GAP = 44f

    /** The scribe's x-height (docs/08 §3) — tuned to sit with the user's hand. */
    private const val BODY_X_HEIGHT = 44f
    private const val HEAD_X_HEIGHT = 56f

    /** Reveal pacing: one tick per ~60 page px of stroke arc. */
    private const val PX_PER_TICK = 60f

    /** Bullet text hangs this far right of the margin (in x-height %). */
    private const val BULLET_TEXT_INDENT_UNITS = 110f

    /** Lays the group out starting at [startY] (page space). */
    fun layout(group: AnswerGroup, pageWidth: Float, startY: Float, context: Context): Layout {
        val items = ArrayList<Layout.Item>()
        val usable = pageWidth - 2 * MARGIN
        var y = startY
        val seed = group.id.hashCode().toLong()

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

        var blockIndex = 0
        for (block in group.blocks) {
            when (block.type) {
                "text" -> y = addTextBlock(block.markdown.orEmpty(), items, usable, y, seed + blockIndex * 7919L)
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
            blockIndex++
        }

        // ── end flourish ──
        y += 10
        addRule(y, widthPx = 2f, color = MagicTheme.INK_SOFT)
        y += 14
        addText("✧", FLOURISH_SIZE, FLOURISH_SIZE + 8f, Fonts.greatVibes, MagicTheme.ACCENT_RED)
        y += 16

        return Layout(items, y - startY)
    }

    // ------------------------------------------------------------ text as ink

    /**
     * Writes one text block as synthesized pen strokes: headings, bullet
     * items and paragraphs, wrapped by the hand domain at the block width.
     * Returns the new y.
     */
    private fun addTextBlock(
        markdown: String,
        items: MutableList<Layout.Item>,
        usable: Float,
        startY: Float,
        seed: Long,
    ): Float {
        var y = startY
        val chunks = normalize(markdown)
            .split(Regex("\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        var ci = 0
        for (chunk in chunks) {
            for (rawLine in chunk.split('\n')) {
                val line = rawLine.trimEnd()
                when {
                    line.startsWith("## ") -> {
                        y = scribeLine(
                            plain(line.removePrefix("## ").trim()), items, usable, y,
                            varySeed(seed, ci * 5 + 1), HEAD_X_HEIGHT, 0f,
                        )
                    }
                    line.startsWith("- ") -> {
                        val text = plain(line.removePrefix("- ").trim())
                        if (text.isEmpty()) continue
                        y = scribeLine(text, items, usable, y, varySeed(seed, ci * 5 + 2), BODY_X_HEIGHT, bullet = true)
                    }
                    else -> {
                        y = scribeLine(plain(line), items, usable, y, varySeed(seed, ci * 5 + 3), BODY_X_HEIGHT, 0f)
                    }
                }
            }
            y += BLOCK_GAP * 0.6f
            ci++
        }
        return y
    }

    /**
     * Scribes one (possibly wrapping) run of text at [xHeightPx] scale into
     * pen-stroke items. When [bullet], a dash mark is drawn ahead of the
     * first line and continuation lines hang under the text.
     */
    private fun scribeLine(
        text: String,
        items: MutableList<Layout.Item>,
        usable: Float,
        startY: Float,
        seed: Long,
        xHeightPx: Float,
        indentUnits: Float = 0f,
        bullet: Boolean = false,
    ): Float {
        val u = xHeightPx / 100f
        val maxWidthUnits = usable / u
        // bullet text starts after the dash; continuation lines align with it
        val textIndent = if (bullet) BULLET_TEXT_INDENT_UNITS else indentUnits
        val res = Scribe.writeRun(text, maxWidthUnits, seed, x0Units = textIndent, indentUnits = textIndent)
        if (res.lines.isEmpty()) return startY

        var yTop = startY
        var lastBaseline = startY
        for ((li, line) in res.lines.withIndex()) {
            val baseline = yTop - line.topUnits * u
            lastBaseline = baseline
            if (li == 0 && bullet) addBulletDash(items, MARGIN + 24f * u, baseline, u)
            for (st in line.strokes) {
                val n = st.pointCount
                val xys = FloatArray(n * 2)
                val widths = FloatArray(n)
                for (i in 0 until n) {
                    xys[i * 2] = MARGIN + st.xys[i * 2] * u
                    xys[i * 2 + 1] = baseline + st.xys[i * 2 + 1] * u
                    widths[i] = st.widths[i] * u
                }
                val lenPx = st.length * u
                val ticks = (lenPx / PX_PER_TICK).toInt().coerceIn(2, 14)
                items.add(Layout.Item.Stroke(xys, widths, lenPx, ticks))
            }
            yTop = baseline + line.pitchUnits * u
        }
        // the block's bottom: the last baseline plus its descenders
        val last = res.lines.last()
        return lastBaseline + last.bottomUnits * u + 8f
    }

    /** A short dash with a rising tail — the scribe's list mark. */
    private fun addBulletDash(items: MutableList<Layout.Item>, x: Float, baseline: Float, u: Float) {
        val xys = floatArrayOf(
            x + 6f * u, baseline - 62f * u,
            x + 20f * u, baseline - 58f * u,
            x + 34f * u, baseline - 62f * u,
            x + 42f * u, baseline - 52f * u,
        )
        val w = 8f * u
        items.add(Layout.Item.Stroke(xys, floatArrayOf(w, w, w, w), 44f * u, 2))
    }

    /**
     * Strips the markdown subset markers for canvas ink and maps typographic
     * characters the hand cannot write to their plain forms.
     */
    fun plain(markdown: String): String =
        normalize(markdown)
            .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
            .replace(Regex("""\*([^*]+)\*"""), "$1")
            .replace("`", "")

    /** Typographic characters → forms the scribe's alphabet knows. */
    private fun normalize(s: String): String = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                '\u2018', '\u2019' -> append('\'')
                '\u201C', '\u201D' -> append('"')
                '\u2026' -> append("...")
                '\u2013', '\u2014' -> append('-')
                '\u00A0' -> append(' ')
                '\u2011' -> append('-')
                else -> append(ch)
            }
        }
    }

    private fun varySeed(base: Long, i: Int): Long = base * 31L + i * 104729L

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

    private fun pathLength(path: android.graphics.Path): Float {
        val m = android.graphics.PathMeasure(path, false)
        var total = m.length
        while (m.nextContour()) total += m.length
        return total
    }

    private const val HEADING_SIZE = 64f
}
