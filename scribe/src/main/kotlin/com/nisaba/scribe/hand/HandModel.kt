package com.nisaba.scribe.hand

/** Pressure→width curve: `w = baseWidth + pressureGain · p^pressureExp` (calibrated on-device in spike S6). */
data class PenProfile(val baseWidth: Float, val pressureGain: Float, val pressureExp: Float) {
    companion object {
        val DEFAULT = PenProfile(baseWidth = 1.6f, pressureGain = 4.5f, pressureExp = 1.4f)
    }
}

/** One pen-down run of a glyph: x,y pairs in glyph units (baseline y=0, x-height = 1, y up). */
class GlyphStroke(val pts: FloatArray, val delayed: Boolean) {
    val size: Int get() = pts.size / 2
}

class Glyph(val adv: Float, val strokes: List<GlyphStroke>)

/** A whole hand: glyph geometry keyed by character. */
class GlyphSet(
    val name: String,
    val ascender: Float,
    val descender: Float,
    val glyphs: Map<Char, Glyph>,
    val capScale: Float = 1f,
    /** Capital height in glyph units (x-height = 1), before [capScale]. */
    val capHeight: Float = 1.9f,
    /** Greek letters by name ("alpha", "Sigma", …) in the same units as [glyphs]. */
    val math: Map<String, Glyph> = emptyMap(),
) {
    private val composed = java.util.concurrent.ConcurrentHashMap<Char, Any>() // composing normalises Unicode: do it once

    /** Character → glyph, composing accents/ligatures the font lacks (see [GlyphCompositionRules]). */
    fun resolve(c: Char): Glyph? = glyphs[c] ?: composed.getOrPut(c) { composeGlyph(c, this) ?: NONE } as? Glyph

    private companion object {
        val NONE = Any()
    }
}

/** A humanized pen stroke in page coordinates, before timing. [ps] is pressure 0..1 per point. */
class LaidStroke(
    val page: Int,
    val xs: FloatArray,
    val ys: FloatArray,
    val ps: FloatArray,
    val delayed: Boolean,
    val wordIndex: Int,
    val charIndex: Int,
    val tone: com.nisaba.scribe.layout.InkTone = com.nisaba.scribe.layout.InkTone.Plain,
)

/** How "human" the hand is. [Perfect] is the schoolbook render used while authoring glyphs. */
data class HumanParams(
    val slantDeg: Float,
    val slantJitterDeg: Float,
    val scaleJitter: Float,
    val rotJitterDeg: Float,
    val offsetJitter: Float,
    val wobble: Float,
    val baselineWander: Float,
    val baseSlopeDeg: Float,
    /** Join adjacent letters with a soft link (semi-cursive) — for print-style fonts. */
    val joinLetters: Boolean = false,
) {
    companion object {
        val CASUAL = HumanParams(12f, 1.6f, 0.03f, 1f, 0.018f, 0.008f, 0.05f, 0.6f, joinLetters = true)
        val FORMAL = HumanParams(10f, 0.8f, 0.02f, 0.5f, 0.012f, 0.005f, 0.03f, 0.3f)
        val PERFECT = HumanParams(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    }
}
