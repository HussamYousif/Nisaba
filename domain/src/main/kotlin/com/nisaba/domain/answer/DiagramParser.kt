package com.nisaba.domain.answer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val MAX_CHARS = 4_000
private const val MAX_ELEMENTS = 60
private const val CURVE_STEPS = 10
private const val ROUND_STEPS = 40
private const val START_ANGLE = 200.0 * PI / 180.0 // circles start at the lower left, like a hand would
private const val DEFAULT_VIEW = 1000f

private val ELEMENT_RE = Regex("""<\s*(path|line|polyline|polygon|rect|circle|ellipse)\b([^>]*?)/?>""", RegexOption.IGNORE_CASE)
private val ATTR_RE = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
private val PATH_TOKEN_RE = Regex("""[MmLlHhVvQqCcSsTtAaZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")
private val NUMBER_RE = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")

private fun attrs(text: String): Map<String, String> =
    ATTR_RE.findAll(text).associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }

private fun nums(s: String?): List<Float> = s?.let { NUMBER_RE.findAll(it).mapNotNull { m -> m.value.toFloatOrNull() }.toList() }.orEmpty()

/**
 * The SVG subset the model is allowed to draw with (path M L H V Q C S T Z, line, polyline, polygon, rect, circle,
 * ellipse; stroke only). Returns null for oversized or empty drawings so the caller can fall back to text.
 */
fun parseDiagram(svg: String): DiagramDrawing? {
    if (svg.length > MAX_CHARS) return null
    val vb = attrs(svg.substringBefore('>')).let { nums(it["viewbox"]) }
    val (vw, vh) = if (vb.size == 4 && vb[2] > 0f && vb[3] > 0f) vb[2] to vb[3] else DEFAULT_VIEW to DEFAULT_VIEW
    val matches = ELEMENT_RE.findAll(svg).toList()
    if (matches.isEmpty() || matches.size > MAX_ELEMENTS) return null
    val shapes = matches.flatMap { m -> shapesOf(m.groupValues[1].lowercase(), attrs(m.groupValues[2])) }
    return if (shapes.isEmpty()) null else DiagramDrawing(vw, vh, shapes)
}

private fun shapesOf(tag: String, a: Map<String, String>): List<DiagramShape> = when (tag) {
    "path" -> pathShapes(a["d"].orEmpty())
    "line" -> listOf(poly(listOf(a.f("x1"), a.f("y1"), a.f("x2"), a.f("y2")), closed = false))
    "polyline" -> listOfNotNull(nums(a["points"]).takeIf { it.size >= 4 }?.let { poly(it, closed = false) })
    "polygon" -> listOfNotNull(nums(a["points"]).takeIf { it.size >= 4 }?.let { poly(it, closed = true) })
    "rect" -> listOf(rect(a.f("x"), a.f("y"), a.f("width"), a.f("height")))
    "circle" -> listOf(ellipse(a.f("cx"), a.f("cy"), a.f("r"), a.f("r")))
    "ellipse" -> listOf(ellipse(a.f("cx"), a.f("cy"), a.f("rx"), a.f("ry")))
    else -> emptyList()
}

private fun Map<String, String>.f(k: String) = this[k]?.let { NUMBER_RE.find(it)?.value?.toFloatOrNull() } ?: 0f

private fun poly(v: List<Float>, closed: Boolean): DiagramShape {
    val n = v.size / 2
    return DiagramShape(FloatArray(n) { v[it * 2] }, FloatArray(n) { v[it * 2 + 1] }, closed)
}

private fun rect(x: Float, y: Float, w: Float, h: Float) =
    DiagramShape(floatArrayOf(x, x + w, x + w, x, x), floatArrayOf(y, y, y + h, y + h, y), closed = true)

private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): DiagramShape {
    val xs = FloatArray(ROUND_STEPS + 1) { (cx + rx * cos(START_ANGLE + 2 * PI * it / ROUND_STEPS)).toFloat() }
    val ys = FloatArray(ROUND_STEPS + 1) { (cy + ry * sin(START_ANGLE + 2 * PI * it / ROUND_STEPS)).toFloat() }
    return DiagramShape(xs, ys, closed = true)
}

private class PathWalker {
    val shapes = ArrayList<DiagramShape>()
    private val xs = ArrayList<Float>(); private val ys = ArrayList<Float>()
    private var cx = 0f; private var cy = 0f; private var sx = 0f; private var sy = 0f
    private var lastCtrlX = 0f; private var lastCtrlY = 0f; private var lastCmd = ' '

    private fun add(x: Float, y: Float) { xs += x; ys += y; cx = x; cy = y }

    private fun flush(closed: Boolean) {
        if (xs.size >= 2) shapes += DiagramShape(xs.toFloatArray(), ys.toFloatArray(), closed)
        xs.clear(); ys.clear()
    }

    fun moveTo(x: Float, y: Float) { flush(false); add(x, y); sx = x; sy = y }
    fun lineTo(x: Float, y: Float) {
        if (xs.isEmpty()) add(cx, cy)
        add(x, y)
    }

    fun close() {
        if (xs.isNotEmpty()) {
            add(sx, sy)
            flush(true)
        }
        cx = sx; cy = sy
    }
    fun end() = flush(false)

    fun quad(x1: Float, y1: Float, x: Float, y: Float) {
        val x0 = cx; val y0 = cy
        for (i in 1..CURVE_STEPS) {
            val t = i / CURVE_STEPS.toFloat(); val u = 1 - t
            lineTo(u * u * x0 + 2 * u * t * x1 + t * t * x, u * u * y0 + 2 * u * t * y1 + t * t * y)
        }
        lastCtrlX = x1; lastCtrlY = y1
    }

    fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
        val x0 = cx; val y0 = cy
        for (i in 1..CURVE_STEPS) {
            val t = i / CURVE_STEPS.toFloat(); val u = 1 - t
            lineTo(
                u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x,
                u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y,
            )
        }
        lastCtrlX = x2; lastCtrlY = y2
    }

    /** A smooth curve continues a curve of its kind: S after C or S, T after Q or T. */
    private fun continues(prev: Char, ctrl: Char) = when (ctrl) {
        'C' -> prev.uppercaseChar() == 'C' || prev.uppercaseChar() == 'S'
        'Q' -> prev.uppercaseChar() == 'Q' || prev.uppercaseChar() == 'T'
        else -> false
    }

    /** Smooth-curve commands mirror the previous control point when the previous command was the same kind of curve. */
    fun reflectX(prev: Char, ctrl: Char) = if (continues(prev, ctrl)) 2 * cx - lastCtrlX else cx
    fun reflectY(prev: Char, ctrl: Char) = if (continues(prev, ctrl)) 2 * cy - lastCtrlY else cy
    fun remember(cmd: Char) { lastCmd = cmd }
    val previous: Char get() = lastCmd
    val x: Float get() = cx
    val y: Float get() = cy
}

/** Numbers each path command consumes per segment (A/a consumes 7 but is drawn as a straight chord). */
private val ARITY = mapOf('M' to 2, 'L' to 2, 'H' to 1, 'V' to 1, 'Q' to 4, 'T' to 2, 'C' to 6, 'S' to 4, 'A' to 7)

private fun isCommand(t: String) = t.length == 1 && t[0].isLetter()

/** Walks the tokens of an SVG path `d` attribute, handling relative commands and implicit repeats. */
private class PathReader(private val tokens: List<String>) {
    private var i = 0
    private var cmd = ' '
    private val walker = PathWalker()

    fun read(): List<DiagramShape> {
        while (i < tokens.size) {
            if (isCommand(tokens[i])) {
                cmd = tokens[i][0]; i++
                if (cmd == 'Z' || cmd == 'z') { walker.close(); walker.remember('Z'); continue }
            }
            if (!segment()) skipNumbers()
        }
        walker.end()
        return walker.shapes
    }

    private fun number(): Float? = tokens.getOrNull(i)?.takeIf { !isCommand(it) }?.also { i++ }?.toFloatOrNull()

    private fun skipNumbers() { while (i < tokens.size && !isCommand(tokens[i])) i++ }

    /** Reads and draws one segment of the current command; false if the numbers ran out or there is no command yet. */
    private fun segment(): Boolean {
        val arity = ARITY[cmd.uppercaseChar()] ?: return false
        val v = FloatArray(arity)
        for (k in 0 until arity) v[k] = number() ?: return false
        draw(v)
        walker.remember(cmd)
        return true
    }

    private fun draw(v: FloatArray) {
        val rel = cmd.isLowerCase()
        val ox = if (rel) walker.x else 0f
        val oy = if (rel) walker.y else 0f
        val prev = walker.previous
        when (cmd.uppercaseChar()) {
            'M' -> { walker.moveTo(ox + v[0], oy + v[1]); cmd = if (rel) 'l' else 'L' }
            'L' -> walker.lineTo(ox + v[0], oy + v[1])
            'H' -> walker.lineTo(ox + v[0], walker.y)
            'V' -> walker.lineTo(walker.x, oy + v[0])
            'Q' -> walker.quad(ox + v[0], oy + v[1], ox + v[2], oy + v[3])
            'T' -> walker.quad(walker.reflectX(prev, 'Q'), walker.reflectY(prev, 'Q'), ox + v[0], oy + v[1])
            'C' -> walker.cubic(ox + v[0], oy + v[1], ox + v[2], oy + v[3], ox + v[4], oy + v[5])
            'S' -> walker.cubic(walker.reflectX(prev, 'C'), walker.reflectY(prev, 'C'), ox + v[0], oy + v[1], ox + v[2], oy + v[3])
            'A' -> walker.lineTo(ox + v[5], oy + v[6])
        }
    }
}

private fun pathShapes(d: String): List<DiagramShape> = PathReader(PATH_TOKEN_RE.findAll(d).map { it.value }.toList()).read()
