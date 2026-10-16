package com.nisaba.scribe.layout

import com.nisaba.domain.answer.MathNode
import com.nisaba.scribe.hand.Glyph
import com.nisaba.scribe.hand.GlyphSet
import com.nisaba.scribe.hand.GlyphStroke
import kotlin.math.max
import kotlin.math.min

/** Size of a formula's parts: display (full), text (inline, smaller) and script (sub/superscripts). */
enum class MathStyle(val scale: Float) {
    Display(1f), Text(0.85f), Script(0.65f);

    /** The style of a fraction's numerator/denominator or a script. */
    fun smaller() = when (this) { Display -> Text; Text -> Script; Script -> Script }
}

/** A laid-out piece of formula: pen strokes in x-height units (baseline y=0, y up) inside [0, width]. */
class MathBox(val width: Float, val ascent: Float, val descent: Float, val strokes: List<GlyphStroke>) {
    fun shifted(dx: Float, dy: Float) = MathBox(
        width, ascent + dy, descent + dy,
        strokes.map { s -> GlyphStroke(FloatArray(s.pts.size) { i -> if (i % 2 == 0) s.pts[i] + dx else s.pts[i] + dy }, s.delayed) },
    )

    fun scaled(k: Float) = MathBox(
        width * k, ascent * k, descent * k, strokes.map { s -> GlyphStroke(FloatArray(s.pts.size) { i -> s.pts[i] * k }, s.delayed) },
    )

    fun toGlyph() = Glyph(width, strokes)

    companion object {
        val EMPTY = MathBox(0f, 0f, 0f, emptyList())
    }
}

private const val AXIS = 0.5f              // the height of a fraction bar / minus sign above the baseline
private const val OP_SPACE = 0.22f
private const val LETTER_GAP = 0.02f
private const val FUNCTION_SPACE = 0.16f
private const val SCRIPT_GAP = 0.05f
private const val FALLBACK_ADV = 0.7f
private const val FENCE_PAD = 0.14f

private fun MathBox.beside(other: MathBox, gap: Float = 0f): MathBox {
    val moved = other.shifted(width + gap, 0f)
    return MathBox(width + gap + other.width, max(ascent, other.ascent), min(descent, other.descent), strokes + moved.strokes)
}

private fun path(pts: FloatArray, scale: Float = 1f) = GlyphStroke(FloatArray(pts.size) { pts[it] * scale }, false)

private fun boundsOf(strokes: List<GlyphStroke>): Pair<Float, Float> {
    var lo = 0f; var hi = 0f
    for (s in strokes) for (i in 1 until s.pts.size step 2) { lo = min(lo, s.pts[i]); hi = max(hi, s.pts[i]) }
    return hi to lo
}

private fun glyphBox(g: Glyph, k: Float): MathBox {
    val strokes = g.strokes.map { s -> GlyphStroke(FloatArray(s.pts.size) { s.pts[it] * k }, s.delayed) }
    val (asc, desc) = boundsOf(strokes)
    return MathBox(g.adv * k, asc, desc, strokes)
}

private fun symbolBox(name: String, set: GlyphSet, style: MathStyle): MathBox {
    val k = style.scale
    if (name.length == 1) {
        val c = name[0]
        val g = set.resolve(c) ?: return literal(name, set, style)
        return glyphBox(g, if (c.isUpperCase()) k * set.capScale else k)
    }
    val greek = set.math[name] ?: set.math[name.removePrefix("var")]
    if (greek != null) return glyphBox(greek, k)
    val sp = symbolPaths(name) ?: return literal(name, set, style)
    val strokes = sp.paths.map { path(it, k) }
    val (asc, desc) = boundsOf(strokes)
    return MathBox(sp.adv * k, asc, desc, strokes)
}

/** Text written with the hand's own letters (also the fallback for anything unknown). */
private fun literal(text: String, set: GlyphSet, style: MathStyle): MathBox {
    var box = MathBox.EMPTY
    for (c in text) {
        val g = set.resolve(c)
        val piece = if (g != null) glyphBox(g, style.scale * (if (c.isUpperCase()) set.capScale else 1f))
        else MathBox(FALLBACK_ADV * style.scale, 0f, 0f, emptyList())
        box = box.beside(piece, LETTER_GAP)
    }
    return box
}

private fun binary(name: String) = name in BINARY_OPERATORS

/** \sin, \ln…, also with a script (\sin^2). */
private fun isFunction(node: MathNode): Boolean = when (node) {
    is MathNode.Text -> node.function
    is MathNode.Sup -> isFunction(node.base)
    is MathNode.Sub -> isFunction(node.base)
    is MathNode.SubSup -> isFunction(node.base)
    else -> false
}

private fun opens(node: MathNode) = node is MathNode.Fenced || (node is MathNode.Sym && (node.name == "(" || node.name == "["))

fun layoutMath(node: MathNode, set: GlyphSet, style: MathStyle): MathBox = when (node) {
    is MathNode.Sym -> symbolBox(node.name, set, style)
    is MathNode.Text -> literal(node.s, set, style)
    is MathNode.Row -> row(node.items, set, style)
    is MathNode.Frac -> fraction(node, set, style)
    is MathNode.Sup -> scripted(layoutMath(node.base, set, style), null, layoutMath(node.sup, set, style.smaller()), style)
    is MathNode.Sub -> scripted(layoutMath(node.base, set, style), layoutMath(node.sub, set, style.smaller()), null, style)
    is MathNode.SubSup -> scripted(
        layoutMath(node.base, set, style), layoutMath(node.sub, set, style.smaller()), layoutMath(node.sup, set, style.smaller()), style,
    )
    is MathNode.Sqrt -> radical(node, set, style)
    is MathNode.BigOp -> bigOperator(node, set, style)
    is MathNode.Fenced -> fenced(node.left, layoutMath(node.body, set, style), node.right, style)
    is MathNode.Matrix -> matrix(node, set, style)
}

private fun row(items: List<MathNode>, set: GlyphSet, style: MathStyle): MathBox {
    var box = MathBox.EMPTY
    for ((i, item) in items.withIndex()) {
        val isBinary = item is MathNode.Sym && binary(item.name) && i > 0 && style != MathStyle.Script
        val piece = layoutMath(item, set, style)
        val afterFunction = i > 0 && isFunction(items[i - 1]) && !opens(item)
        val gap = when {
            box.width == 0f -> 0f
            isBinary -> OP_SPACE * style.scale
            afterFunction -> FUNCTION_SPACE * style.scale // "ln W", as in print; but "sin(x)"
            else -> LETTER_GAP
        }
        box = box.beside(piece, gap)
        if (isBinary && i < items.lastIndex) box = MathBox(box.width + OP_SPACE * style.scale, box.ascent, box.descent, box.strokes)
    }
    return box
}

private fun fraction(f: MathNode.Frac, set: GlyphSet, style: MathStyle): MathBox {
    val s = style.scale
    val sub = style.smaller()
    val n = layoutMath(f.num, set, sub); val d = layoutMath(f.den, set, sub)
    val width = max(n.width, d.width) + 0.3f * s
    val bar = AXIS * s; val gap = 0.2f * s
    val numBase = bar + gap - n.descent
    val denBase = bar - gap - d.ascent
    val num = n.shifted((width - n.width) / 2f, numBase)
    val den = d.shifted((width - d.width) / 2f, denBase)
    val rule = GlyphStroke(floatArrayOf(0f, bar, width, bar), false)
    return MathBox(width, num.ascent, den.descent, num.strokes + den.strokes + rule)
}

private fun scripted(base: MathBox, sub: MathBox?, sup: MathBox?, style: MathStyle): MathBox {
    val s = style.scale
    val supY = max(0.55f * s, base.ascent - 0.45f * s)
    val subY = min(-0.28f * s, base.descent - 0.15f * s + 0.1f * s)
    val x = base.width + SCRIPT_GAP
    val width = x + max(sub?.width ?: 0f, sup?.width ?: 0f)
    val parts = listOfNotNull(sup?.shifted(x, supY), sub?.shifted(x, subY))
    return MathBox(
        width, max(base.ascent, sup?.let { supY + it.ascent } ?: 0f), min(base.descent, sub?.let { subY + it.descent } ?: 0f),
        base.strokes + parts.flatMap { it.strokes },
    )
}

private fun radical(r: MathNode.Sqrt, set: GlyphSet, style: MathStyle): MathBox {
    val s = style.scale
    val body = layoutMath(r.body, set, style)
    val index = r.index?.let { layoutMath(it, set, MathStyle.Script) }
    val pad = 0.15f * s
    val h = max(body.ascent, s) + 0.22f * s
    val bottom = min(body.descent, 0f) - 0.05f * s
    val lead = max(0f, (index?.width ?: 0f) - 0.3f * s)
    val hook = 0.56f * s
    val sign = GlyphStroke(
        floatArrayOf(
            lead, 0.42f * h, lead + 0.14f * s, 0.5f * h, lead + 0.3f * s, bottom, lead + hook, h, lead + hook + body.width + 2 * pad, h,
        ),
        false,
    )
    val moved = body.shifted(lead + hook + pad, 0f)
    val idx = index?.shifted(0f, 0.5f * h + 0.1f * s)
    return MathBox(lead + hook + body.width + 2 * pad, h, bottom, listOf(sign) + moved.strokes + idx?.strokes.orEmpty())
}

private fun bigOperator(op: MathNode.BigOp, set: GlyphSet, style: MathStyle): MathBox {
    val s = style.scale
    val limitsAbove = op.op != "int"
    val sub = op.lower?.let { layoutMath(it, set, MathStyle.Script) }
    val sup = op.upper?.let { layoutMath(it, set, MathStyle.Script) }
    val sign = when (op.op) {
        "lim" -> literal("lim", set, style)
        "int" -> symbolBox("int", set, style).scaled(1.5f).shifted(0f, -0.15f * s)
        else -> symbolBox(op.op, set, style).scaled(1.4f).shifted(0f, -0.2f * s)
    }
    if (!limitsAbove) return scripted(sign, sub, sup, style)
    val width = max(sign.width, max(sub?.width ?: 0f, sup?.width ?: 0f))
    val parts = ArrayList<GlyphStroke>()
    parts += sign.shifted((width - sign.width) / 2f, 0f).strokes
    var asc = sign.ascent; var desc = sign.descent
    sup?.let { val y = sign.ascent + 0.1f * s - it.descent; parts += it.shifted((width - it.width) / 2f, y).strokes; asc = y + it.ascent }
    sub?.let { val y = sign.descent - 0.1f * s - it.ascent; parts += it.shifted((width - it.width) / 2f, y).strokes; desc = y + it.descent }
    return MathBox(width, asc, desc, parts)
}

private fun fenced(left: String, body: MathBox, right: String, style: MathStyle): MathBox {
    val s = style.scale
    val top = max(body.ascent, s) + 0.1f * s; val bottom = min(body.descent, 0f) - 0.1f * s
    val w = 0.32f * s
    val l = delimiterPaths(left, left = false, top = top, bottom = bottom, width = w).map { path(it) }
    val r = delimiterPaths(right, left = true, top = top, bottom = bottom, width = w).map { path(it) }
    val lw = if (l.isEmpty()) 0f else w
    val rw = if (r.isEmpty()) 0f else w
    val pad = FENCE_PAD * s
    val inner = body.shifted(lw + pad, 0f)
    val rightShift = lw + pad + body.width + pad
    val rightStrokes = r.map { st ->
        GlyphStroke(FloatArray(st.pts.size) { i -> if (i % 2 == 0) st.pts[i] + rightShift else st.pts[i] }, false)
    }
    return MathBox(rightShift + rw, top, bottom, l + inner.strokes + rightStrokes)
}

private fun matrix(m: MathNode.Matrix, set: GlyphSet, style: MathStyle): MathBox {
    val s = style.scale
    val cells = m.rows.map { r -> r.map { layoutMath(it, set, style) } }
    val cols = cells.maxOfOrNull { it.size } ?: 0
    if (cols == 0) return MathBox.EMPTY
    val colW = List(cols) { c -> cells.maxOf { it.getOrNull(c)?.width ?: 0f } }
    val rowAsc = cells.map { r -> max(r.maxOfOrNull { it.ascent } ?: 0f, s) }
    val rowDesc = cells.map { r -> min(r.minOfOrNull { it.descent } ?: 0f, 0f) }
    val colGap = 0.7f * s; val rowGap = 0.35f * s
    val height = rowAsc.indices.sumOf { (rowAsc[it] - rowDesc[it]).toDouble() }.toFloat() + rowGap * (cells.size - 1)
    val strokes = ArrayList<GlyphStroke>()
    var y = AXIS * s + height / 2f
    var maxW = 0f
    for ((ri, r) in cells.withIndex()) {
        val baseline = y - rowAsc[ri]
        var x = 0f
        for ((ci, cell) in r.withIndex()) {
            strokes += cell.shifted(x + (colW[ci] - cell.width) / 2f, baseline).strokes
            x += colW[ci] + colGap
        }
        maxW = max(maxW, x - colGap)
        y = baseline + rowDesc[ri] - rowGap
    }
    val body = MathBox(maxW, AXIS * s + height / 2f, AXIS * s - height / 2f, strokes)
    return if (m.open.isEmpty() && m.close.isEmpty()) body else fenced(m.open, body, m.close, style)
}
