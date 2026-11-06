package com.nisaba.scribe.hand

import java.text.Normalizer

private const val MARK_GAP = 0.18f

private enum class Mark(val code: Char) {
    ACUTE('́'), GRAVE('̀'), CIRCUMFLEX('̂'), DIAERESIS('̈'), RING('̊'), TILDE('̃'),
    CARON('̌'), CEDILLA('̧'), OGONEK('̨');

    companion object {
        fun of(c: Char) = entries.firstOrNull { it.code == c }
    }
}

/** Pen path (x,y pairs) for [mark] centred on x=0 at height [y]; sizes are in x-height units. */
private fun markStrokes(mark: Mark, cx: Float, y: Float): List<GlyphStroke> {
    fun s(vararg p: Float) = GlyphStroke(floatArrayOf(*p), delayed = true)
    return when (mark) {
        Mark.ACUTE -> listOf(s(cx - 0.10f, y, cx + 0.12f, y + 0.30f))
        Mark.GRAVE -> listOf(s(cx + 0.10f, y, cx - 0.12f, y + 0.30f))
        Mark.CIRCUMFLEX -> listOf(s(cx - 0.16f, y, cx, y + 0.28f, cx + 0.16f, y))
        Mark.CARON -> listOf(s(cx - 0.16f, y + 0.28f, cx, y, cx + 0.16f, y + 0.28f))
        Mark.DIAERESIS -> listOf(s(cx - 0.16f, y + 0.12f, cx - 0.14f, y + 0.14f), s(cx + 0.14f, y + 0.12f, cx + 0.16f, y + 0.14f))
        Mark.RING -> listOf(s(cx + 0.12f, y + 0.14f, cx, y + 0.28f, cx - 0.12f, y + 0.14f, cx, y, cx + 0.12f, y + 0.14f))
        Mark.TILDE -> listOf(
            s(cx - 0.22f, y + 0.05f, cx - 0.10f, y + 0.22f, cx + 0.02f, y + 0.10f, cx + 0.14f, y + 0.24f, cx + 0.24f, y + 0.12f),
        )
        Mark.CEDILLA -> listOf(s(cx - 0.02f, -0.02f, cx + 0.04f, -0.20f, cx - 0.06f, -0.34f))
        Mark.OGONEK -> listOf(s(cx + 0.08f, -0.02f, cx + 0.16f, -0.20f, cx + 0.06f, -0.30f))
    }
}

private fun shift(g: Glyph, dx: Float, sx: Float = 1f): List<GlyphStroke> = g.strokes.map { st ->
    GlyphStroke(FloatArray(st.pts.size) { i -> if (i % 2 == 0) st.pts[i] * sx + dx else st.pts[i] }, st.delayed)
}

private fun isUpper(base: Char) = base.isUpperCase()

/** How far a middle dot sits above the baseline, in x-heights. */
private const val MIDDLE_DOT_RISE = 0.4f

private fun raise(g: Glyph, dy: Float): List<GlyphStroke> = g.strokes.map { st ->
    GlyphStroke(FloatArray(st.pts.size) { i -> if (i % 2 == 1) st.pts[i] + dy else st.pts[i] }, st.delayed)
}

/** A tick and a cross, as a hand makes them (x-height units); models like to mark checked steps with them. */
private val TICK = Glyph(0.95f, listOf(GlyphStroke(floatArrayOf(0.08f, 0.48f, 0.34f, 0.02f, 0.88f, 1.10f), delayed = false)))
private val CROSS = Glyph(
    0.85f,
    listOf(
        GlyphStroke(floatArrayOf(0.10f, 0.95f, 0.72f, 0.05f), delayed = false),
        GlyphStroke(floatArrayOf(0.10f, 0.05f, 0.72f, 0.95f), delayed = false),
    ),
)

/** Builds a glyph the font lacks from a base letter plus accent strokes, a couple of special ligatures, or a tick/cross. */
fun composeGlyph(c: Char, set: GlyphSet): Glyph? {
    when (c) {
        '✓', '✔', '☑' -> return TICK
        '✗', '✘', '☒' -> return CROSS
        'æ' -> return ligature(set, 'a', 'e')
        'Æ' -> return ligature(set, 'A', 'E')
        'ø' -> return slashed(set, 'o')
        'Ø' -> return slashed(set, 'O')
        '·' -> return set.glyphs['.']?.let { d -> Glyph(d.adv, raise(d, MIDDLE_DOT_RISE)) } // "A · B · C"
        'ß' -> return set.glyphs['s']?.let { s -> Glyph(s.adv * 1.1f, shift(s, 0f)) } // approximation: long-s look
    }
    val nfd = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
    if (nfd.length < 2) return null
    val base = set.glyphs[nfd[0]] ?: return null
    val marks = nfd.drop(1).mapNotNull { Mark.of(it) }
    if (marks.isEmpty()) return null
    val cx = base.adv / 2f
    val top = if (isUpper(nfd[0])) set.capHeight else 1f
    val extra = marks.flatMap { m ->
        val y = if (m == Mark.CEDILLA || m == Mark.OGONEK) 0f else top + MARK_GAP
        markStrokes(m, cx, y)
    }
    return Glyph(base.adv, base.strokes + extra)
}

private fun ligature(set: GlyphSet, a: Char, b: Char): Glyph? {
    val ga = set.glyphs[a] ?: return null
    val gb = set.glyphs[b] ?: return null
    val overlap = 0.25f
    val squeeze = 0.85f
    val second = shift(gb, ga.adv * squeeze - overlap, squeeze)
    return Glyph(ga.adv * squeeze + gb.adv * squeeze - overlap, shift(ga, 0f, squeeze) + second)
}

private fun slashed(set: GlyphSet, base: Char): Glyph? {
    val g = set.glyphs[base] ?: return null
    val top = if (base.isUpperCase()) set.capHeight else 1f
    val slash = GlyphStroke(floatArrayOf(g.adv * 0.9f, top * 1.05f, g.adv * 0.1f, -top * 0.1f), delayed = false)
    return Glyph(g.adv, g.strokes + slash)
}
