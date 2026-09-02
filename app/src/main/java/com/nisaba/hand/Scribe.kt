package com.nisaba.hand

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The scribe's hand (docs/08 §3): turns plain text into PEN STROKES — the
 * same kind of ink the user writes with, laid out as a hand would lay it
 * out: connected cursive runs, natural per-letter variation, wandering
 * baselines, i-dots and t-crossings drawn after each word.
 *
 * Pure geometry: no android imports. Units are % of x-height (x-height =
 * 100). Glyphs are authored in paper space (y up, baseline 0); emitted
 * strokes are in CANVAS space (y down) with the line's undistorted baseline
 * at y = 0 — a renderer only scales and translates.
 *
 * The writing model is the connected wave: every lowercase letter begins at
 * its entry base on the baseline and its last stroke returns to the baseline
 * at its exit; consecutive letters share that point (the pen bounces at the
 * baseline instead of lifting), so a word is drawn as one continuous stroke.
 *
 * Determinism: everything derives from [seed]; the same text, width and seed
 * always produce identical strokes, so a saved answer re-inks itself
 * identically on reopen.
 */
object Scribe {

    /** How strongly the hand varies (0 = perfect schoolbook, 1 = human). */
    class Options(
        val variation: Float = 1f,
        /** Rightward lean: horizontal shift per unit of height above the baseline. */
        val slant: Float = 0.13f,
        /** Pen-pressure width modulation (thick downstrokes, thin upstrokes). */
        val pressure: Boolean = true,
    )

    class Result(
        val lines: List<Line>,
        /** Total height (y-down) of the run, from its top ink to its bottom ink. */
        val heightUnits: Float,
    ) {
        val isBlank: Boolean get() = lines.all { it.isBlank }
    }

    class Line(
        /** y-down distance from the baseline to the line's highest ink. */
        val topUnits: Float,
        /** y-down distance from the baseline to the line's lowest ink. */
        val bottomUnits: Float,
        /** Baseline-to-baseline distance to the next line. */
        val pitchUnits: Float,
        val strokes: List<Stroke>,
    ) {
        val isBlank: Boolean get() = strokes.isEmpty()
    }

    /** One pen-down run: a dense polyline with a width per point. */
    class Stroke(
        /** x,y pairs (y-down, baseline-relative); size == widths.size. */
        val xys: FloatArray,
        val widths: FloatArray,
        /** Arc length in units (reveal pacing). */
        val length: Float,
    ) {
        val pointCount: Int get() = xys.size / 2
    }

    // ------------------------------------------------------------ metrics

    /** Typical word space, after a word's terminal flick. */
    private const val WORD_GAP = 50f
    /** Baseline-to-baseline line pitch. */
    private const val LINE_PITCH = 248f
    /** Pen width at a mid-pressure horizontal stroke (% of x-height). */
    private const val BASE_WIDTH = 7.2f

    private val FALLBACK_KNOTS = floatArrayOf(0f, 26f, 7f, 34f, 14f, 27f, 21f, 33f, 28f, 26f)

    // ------------------------------------------------------------ rng

    // SplitMix64 constants as signed longs (two's complement of the usual hex).
    private const val GOLDEN = -7046029254386353131L
    private const val MIX1 = -4654470214887392327L
    private const val MIX2 = -7723592293110705685L

    private fun mix(s: Long): Long {
        var z = s + GOLDEN
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    private fun unit(s: Long): Float {
        val u = mix(s) ushr 11
        return (u and 0x1FFFFF).toFloat() / 0x1FFFFF
    }

    private fun range(s: Long, a: Float, b: Float): Float = a + (b - a) * unit(s)

    private fun vary(seed: Long, i: Int): Long = mix(seed * 31L + i.toLong() * GOLDEN)

    // ------------------------------------------------------------ splines

    /**
     * Samples the Catmull-Rom spline through [knots] into [into] as cubic
     * segments. A knot repeated twice makes a corner at it. [tr] maps each
     * knot's local (x, yUp) to absolute coordinates before fitting, so
     * per-letter variation (scale/rotation/slant) applies to the geometry
     * exactly (affine on the curve = affine on the knots).
     */
    private fun emitKnots(
        into: MutableList<FloatArray>,
        knots: List<FloatArray>,
        tr: (x: Float, y: Float) -> FloatArray,
    ) {
        val n = knots.size
        if (n < 2) return
        val p = ArrayList<FloatArray>(n + 2)
        for (k in knots) p.add(tr(k[0], k[1]))
        into.add(floatArrayOf(p[0][0], p[0][1]))
        for (i in 0 until n - 1) {
            val p0 = p[maxOf(i - 1, 0)]
            val p1 = p[i]
            val p2 = p[i + 1]
            val p3 = p[minOf(i + 2, n - 1)]
            val c1x = p1[0] + (p2[0] - p0[0]) / 6f
            val c1y = p1[1] + (p2[1] - p0[1]) / 6f
            val c2x = p2[0] - (p3[0] - p1[0]) / 6f
            val c2y = p2[1] - (p3[1] - p1[1]) / 6f
            sampleCubic(into, p1[0], p1[1], c1x, c1y, c2x, c2y, p2[0], p2[1])
        }
    }

    private fun sampleCubic(
        into: MutableList<FloatArray>,
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ) {
        val len = hypot(bx - ax, by - ay) + hypot(cx - bx, cy - by) + hypot(dx - cx, dy - cy)
        val n = (len / 5f).toInt().coerceIn(3, 30)
        for (i in 1..n) {
            val t = i.toFloat() / n
            val mt = 1f - t
            val wx = mt * mt * mt * ax + 3f * mt * mt * t * bx + 3f * mt * t * t * cx + t * t * t * dx
            val wy = mt * mt * mt * ay + 3f * mt * mt * t * by + 3f * mt * t * t * cy + t * t * t * dy
            into.add(floatArrayOf(wx, wy))
        }
    }

    // ------------------------------------------------------------ public

    /**
     * Writes [text] (a paragraph; '\n' forces a line break) into pen strokes
     * wrapped at [maxWidthUnits]. The first line starts at [x0Units];
     * continuation lines are indented [indentUnits]. Strokes are y-down,
     * baseline-relative.
     */
    fun writeRun(
        text: String,
        maxWidthUnits: Float,
        seed: Long,
        x0Units: Float = 0f,
        indentUnits: Float = 0f,
        options: Options = Options(),
    ): Result {
        val lines = ArrayList<Line>()
        val words = splitWords(text)
        var y = 0f // y-down offset of the current line's baseline
        var firstOfLine = true
        val lineWords = ArrayList<WordAt>()
        var lineWidth = 0f
        var wi = 0

        fun flush() {
            if (lineWords.isNotEmpty()) {
                lines.add(shapeLine(lineWords, seed, y, options))
                y += LINE_PITCH
                lineWords.clear()
                lineWidth = 0f
            }
        }

        for (w in words) {
            when {
                w == "\n" -> {
                    flush()
                    firstOfLine = true
                }
                w.isEmpty() -> Unit
                else -> {
                    val m = measureWord(w, seed, wi, options)
                    val indent = if (firstOfLine) x0Units else indentUnits
                    val avail = maxWidthUnits - indent
                    if (!firstOfLine && lineWidth + WORD_GAP + m > avail) {
                        flush()
                        firstOfLine = true // this word now opens the new line
                    }
                    if (lineWidth > 0f) lineWidth += WORD_GAP
                    // a lone word wider than the line is squeezed to fit
                    // rather than spilling past the margin
                    val scale = if (firstOfLine && m > avail) ((avail - 6f) / m).coerceAtLeast(0.03f) else 1f
                    lineWidth += m * scale
                    lineWords.add(WordAt(wi, w, scale))
                    firstOfLine = false
                    wi++
                }
            }
        }
        flush()
        val height = if (lines.isEmpty()) 0f else -lines.first().topUnits + y
        return Result(lines, height)
    }

    private fun splitWords(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (c in text) {
            if (c == '\n') {
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                out.add("\n")
            } else if (c == ' ' || c == '\t') {
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
            } else cur.append(c)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /** Horizontal advance (units) the word occupies, incl. its end flick. */
    private fun measureWord(text: String, seed: Long, idx: Int, options: Options): Float {
        var x = 0f
        for (c in text) {
            val g = Glyphs.of(c)
            if (g != null) {
                x += if (c in 'a'..'z' && g.strokes.size == 1) g.exitX else g.maxX + 7f
            } else {
                x += 52f
            }
        }
        return x + range(vary(seed, idx * 101 + 900), 14f, 22f)
    }

    private class WordAt(val idx: Int, val text: String, val scale: Float)

    /**
     * Lays one line's words onto a wandering baseline and emits every stroke
     * in pen order. [y] is the y-down offset of the line's baseline.
     */
    private fun shapeLine(
        words: List<WordAt>,
        seed: Long,
        y: Float,
        options: Options,
    ): Line {
        val wanderA = range(vary(seed, 900), 3.2f, 4.8f) * options.variation
        val wanderF = range(vary(seed, 901), 0.013f, 0.02f)
        val wanderP = range(vary(seed, 902), 0f, 6.283f)
        val drift = range(vary(seed, 903), -0.011f, 0.011f) * options.variation
        val driftC = cos(drift)
        val driftS = sin(drift)

        fun wander(x: Float): Float =
            wanderA * sin(x * wanderF + wanderP) +
                1.4f * options.variation * sin(x * wanderF * 2.7f + wanderP * 1.7f + 1.1f)

        // 1. Compose every word into raw (x, yUp) samples per pen stroke.
        val penStrokes = ArrayList<List<FloatArray>>()
        var cursor = 0f
        var firstWord = true
        for (w in words) {
            val s = vary(seed, w.idx * 3 + 1)
            val gap = WORD_GAP * (range(s, 0.78f, 1.25f) * options.variation + 1f - options.variation)
            if (!firstWord) cursor += gap
            cursor = composeWord(w.text, w.idx, s, cursor, penStrokes, options, w.scale)
            firstWord = false
        }

        // 2. Map to y-down, apply wander + drift, measure extents, and give
        // the ink a pen's pressure profile (thick downstrokes, thin
        // upstrokes, ramps where the pen lands and lifts). Short strokes
        // (dots, punctuation stabs) get a constant heavy width instead —
        // a hand stabs a dot, it does not sweep it.
        val strokes = ArrayList<Stroke>(penStrokes.size)
        var top = 0f
        var bottom = 0f
        for (raw in penStrokes) {
            if (raw.size < 2) continue
            val n = raw.size
            val xys = FloatArray(n * 2)
            val widths = FloatArray(n)
            var len = 0f
            var px = 0f; var py = 0f
            for (i in 0 until n) {
                val xUp = raw[i][0]
                val yUp = raw[i][1]
                val wob = wander(xUp)
                // y-down, baseline-relative, drift-rotated about the baseline origin
                val yd = wob - yUp
                val xr = xUp * driftC - yd * driftS
                val yr = xUp * driftS + yd * driftC
                xys[i * 2] = xr
                xys[i * 2 + 1] = yr
                if (yr < top) top = yr
                if (yr > bottom) bottom = yr
                if (i > 0) len += hypot(xr - px, yr - py)
                px = xr; py = yr
            }
            if (n < 8) {
                // a stab: short, heavy, no direction modulation
                for (i in 0 until n) widths[i] = BASE_WIDTH * 1.15f
            } else if (!options.pressure) {
                for (i in 0 until n) widths[i] = BASE_WIDTH
            } else {
                for (i in 0 until n) {
                    val ax = xys[i * 2]
                    val ay = xys[i * 2 + 1]
                    val bx = if (i < n - 1) xys[(i + 1) * 2] else xys[(i - 1) * 2]
                    val by = if (i < n - 1) xys[(i + 1) * 2 + 1] else xys[(i - 1) * 2 + 1]
                    // direction of travel through this sample (y-down)
                    val dy = by - ay
                    val dx = bx - ax
                    val d = hypot(dx, dy).coerceAtLeast(0.001f)
                    val v = dy / d // -1 upstroke … +1 downstroke
                    val press = if (v >= 0f) 1f + 0.52f * v else 1f + 0.3f * v
                    widths[i] = BASE_WIDTH * press
                }
                // stroke-end ramps: the pen lands thin and lifts thin
                val rampDown = minOf(4, n - 1)
                val rampUp = minOf(6, n - 1)
                for (i in 1..rampDown) {
                    val t = i.toFloat() / rampDown
                    widths[i] = widths[i] * (0.35f + 0.65f * t)
                }
                widths[0] = widths[1] * 0.35f
                for (i in 1..rampUp) {
                    val t = i.toFloat() / rampUp
                    widths[n - i] = widths[n - i] * (1f - 0.45f * t)
                }
                // smooth the profile so width changes are gradual
                val smoothed = FloatArray(n)
                for (i in 0 until n) {
                    var acc = 0f
                    var wsum = 0f
                    for (j in maxOf(0, i - 2)..minOf(n - 1, i + 2)) {
                        acc += widths[j]
                        wsum++
                    }
                    smoothed[i] = acc / wsum
                }
                for (i in 0 until n) widths[i] = smoothed[i]
            }
            strokes.add(Stroke(xys, widths, len))
        }
        if (strokes.isEmpty()) return Line(0f, 0f, LINE_PITCH, strokes)
        return Line(top - 5f, bottom + 5f, LINE_PITCH, strokes)
    }

    // ------------------------------------------------------------ words

    private class Mark(val x: Float, val yUp: Float, val cross: Boolean)

    /**
     * Writes one word: its connected cursive body, then the i-dots and
     * t-crossings. Returns the cursor x after the word (incl. terminal flick).
     */
    private fun composeWord(
        text: String,
        idx: Int,
        seed: Long,
        startX: Float,
        out: MutableList<List<FloatArray>>,
        options: Options,
        wordScale: Float = 1f,
    ): Float {
        val body = ArrayList<FloatArray>(512)
        var cursor = startX
        var penDown = false
        val marks = ArrayList<Mark>()

        fun lift() {
            if (penDown && body.size >= 2) {
                out.add(body.toList())
            }
            body.clear()
            penDown = false
        }

        var letter = 0
        var lastLower = false
        for (c in text) {
            val glyph = Glyphs.of(c)
            val lower = c in 'a'..'z'
            if (glyph == null) {
                lift()
                val fb = ArrayList<FloatArray>(FALLBACK_KNOTS.size / 2)
                for (i in 0 until FALLBACK_KNOTS.size / 2) {
                    fb.add(floatArrayOf(cursor + FALLBACK_KNOTS[i * 2] * 2f, FALLBACK_KNOTS[i * 2 + 1]))
                }
                out.add(fb)
                cursor += 52f
                lastLower = false
                letter++
                continue
            }
            val ls = vary(seed, letter * 7 + 2)
            val sx = (range(ls, 0.97f, 1.03f) * options.variation + 1f * (1f - options.variation)) * wordScale
            val sy = (range(vary(ls, 1), 0.98f, 1.02f) * options.variation + 1f * (1f - options.variation)) * wordScale
            val rot = range(vary(ls, 2), -0.008f, 0.008f) * options.variation
            val slant = options.slant + range(vary(ls, 4), -0.02f, 0.02f) * options.variation
            val bob = range(vary(ls, 5), -1.2f, 1.2f) * options.variation
            val cosR = cos(rot)
            val sinR = sin(rot)
            val baseX = cursor
            val singleWave = glyph.strokes.size == 1 && lower
            val connects = penDown && lastLower && singleWave
            if (!connects) lift()
            penDown = true
            var glyphMaxX = baseX

            fun tr(x: Float, y: Float): FloatArray {
                val xx = x * sx
                val yy = y * sy
                val rx = xx * cosR - yy * sinR
                val ry = xx * sinR + yy * cosR
                val px = baseX + rx + ry * slant
                if (px > glyphMaxX) glyphMaxX = px
                return floatArrayOf(px, ry + bob)
            }

            var strokeIdx = 0
            for (st in glyph.strokes) {
                if (strokeIdx > 0) {
                    lift()
                    penDown = true
                    val first = tr(st.knots[0][0], st.knots[0][1])
                    body.add(first)
                    if (st.knots.size > 1) emitKnots(body, st.knots.subList(1, st.knots.size), ::tr)
                } else {
                    emitKnots(body, st.knots, ::tr)
                }
                strokeIdx++
            }

            val lastKnot = glyph.strokes.last().knots.last()
            val exit = tr(lastKnot[0], lastKnot[1])
            glyph.dot?.let { d ->
                val a = tr(d[0], d[1])
                marks.add(Mark(a[0], a[1], cross = false))
            }
            if (glyph.crossX != 0f || glyph.crossY != 0f) {
                val a = tr(glyph.crossX, glyph.crossY)
                marks.add(Mark(a[0], a[1], cross = true))
            }
            if (singleWave) {
                // connected: the next letter's upstroke bounces off this exit
                cursor = exit[0] + range(vary(ls, 6), -1.6f, 1.6f) * options.variation * wordScale
            } else {
                // the pen lifts: the next letter starts past the rightmost ink
                cursor = glyphMaxX + (range(vary(ls, 7), 4f, 10f) * options.variation + 6f * (1f - options.variation)) * wordScale
            }
            lastLower = lower
            letter++
        }

        // The terminal flick: the pen leaves the word rising, then lifts.
        if (penDown && body.size >= 2) {
            val last = body.last()
            val prev = body[maxOf(body.size - 2, 0)]
            var dx = last[0] - prev[0]
            var dy = last[1] - prev[1]
            val d = hypot(dx, dy).coerceAtLeast(1f)
            dx /= d; dy /= d
            val flickLen = range(vary(seed, 11), 18f, 30f) * wordScale
            val rise = range(vary(seed, 12), 0.35f, 0.6f)
            body.add(floatArrayOf(last[0] + dx * flickLen * 0.4f, last[1] + dy * flickLen * 0.4f + flickLen * rise * 0.2f))
            body.add(floatArrayOf(last[0] + dx * flickLen * 0.75f, last[1] + dy * flickLen * 0.75f + flickLen * rise * 0.5f))
            val endX = last[0] + dx * flickLen
            body.add(floatArrayOf(endX, last[1] + dy * flickLen + flickLen * rise))
            cursor = maxOf(cursor, endX + 2f)
        }
        lift()

        // Marks: t-crossings first (they sit over the body), then dots.
        for (m in marks) {
            if (!m.cross) continue
            crossStroke(m, out)
        }
        for (m in marks) {
            if (m.cross) continue
            dotStroke(m, out)
        }
        return cursor
    }

    private fun dotStroke(m: Mark, out: MutableList<List<FloatArray>>) {
        val pts = ArrayList<FloatArray>(3)
        pts.add(floatArrayOf(m.x - 5f, m.yUp + 1f))
        pts.add(floatArrayOf(m.x, m.yUp))
        pts.add(floatArrayOf(m.x + 5f, m.yUp - 2.5f))
        out.add(pts)
    }

    private fun crossStroke(m: Mark, out: MutableList<List<FloatArray>>) {
        val pts = ArrayList<FloatArray>(4)
        pts.add(floatArrayOf(m.x - 12f, m.yUp - 2.5f))
        pts.add(floatArrayOf(m.x - 4f, m.yUp - 1f))
        pts.add(floatArrayOf(m.x + 4f, m.yUp + 0.5f))
        pts.add(floatArrayOf(m.x + 12f, m.yUp + 2.5f))
        out.add(pts)
    }
}
