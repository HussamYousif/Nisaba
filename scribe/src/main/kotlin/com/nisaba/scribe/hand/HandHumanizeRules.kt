package com.nisaba.scribe.hand

import com.nisaba.scribe.layout.LaidWord
import com.nisaba.scribe.layout.PlacedChar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

private const val BASE_PRESSURE = 0.62f
private const val BOLD_GAIN = 1.35f
private const val RAMP_IN = 0.08f
private const val RAMP_OUT = 0.12f
private const val DOWNSTROKE_BONUS = 0.28f
private const val STAB_LENGTH = 0.5f
private const val STAB_PRESSURE = 0.9f
private const val WOBBLE_PERIOD = 0.8f
private const val DENSE_GAP = 0.12f // max spacing between stroke points, in x-heights

private fun mix(z0: Long): Long {
    var z = z0 + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}

/** Deterministic pseudo-random value in [-1, 1) from (seed, a, b): order-independent, so incremental layout is reproducible. */
fun unitNoise(seed: Long, a: Int, b: Int = 0): Float {
    val h = mix(seed * 31 + a.toLong() * 1_000_003L + b.toLong() * 7_919L)
    return ((h ushr 40).toInt() / 8_388_608f) - 1f
}

/** Roughly Gaussian in about [-1.5, 1.5]. */
fun gaussNoise(seed: Long, a: Int, b: Int = 0): Float =
    (unitNoise(seed, a, b) + unitNoise(seed, a, b + 101) + unitNoise(seed, a, b + 203)) / 2f

private fun smoothNoise(seed: Long, id: Int, t: Float): Float {
    val i = floor(t).toInt(); val f = t - i
    val s = f * f * (3f - 2f * f)
    val a = unitNoise(seed, id, i); val b = unitNoise(seed, id, i + 1)
    return a + (b - a) * s
}

/** Inserts linear intermediate points so no gap exceeds [maxGap]; endpoints and original points are kept. */
private fun densify(xs: FloatArray, ys: FloatArray, maxGap: Float): Pair<FloatArray, FloatArray> {
    if (xs.size < 2) return xs to ys
    val ox = ArrayList<Float>(xs.size * 3); val oy = ArrayList<Float>(xs.size * 3)
    ox += xs[0]; oy += ys[0]
    for (i in 1 until xs.size) {
        val k = kotlin.math.ceil(hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]) / maxGap).toInt().coerceAtLeast(1)
        for (j in 1..k) { val t = j / k.toFloat(); ox += xs[i - 1] + (xs[i] - xs[i - 1]) * t; oy += ys[i - 1] + (ys[i] - ys[i - 1]) * t }
    }
    return ox.toFloatArray() to oy.toFloatArray()
}

private fun degToRad(d: Float) = d * PI.toFloat() / 180f

/** Baseline offset of a whole line: two slow sines plus a small slope, so lines drift like real handwriting. */
fun lineWander(seed: Long, line: Int, x: Float, x0: Float, xh: Float, p: HumanParams): Float {
    val ph1 = unitNoise(seed, line, 1) * PI.toFloat(); val ph2 = unitNoise(seed, line, 2) * PI.toFloat()
    val sines = (sin(ph1 + x / (xh * 14f)) + sin(ph2 + x / (xh * 9f))) / 2f * p.baselineWander * xh
    val slope = tan(degToRad(p.baseSlopeDeg * unitNoise(seed, line, 3))) * (x - x0)
    return sines + slope
}

/** Pressure along a stroke: soft landing, heavier downstrokes, light lift-off; short strokes are stabs (dots, commas). */
private fun pressureProfile(xs: FloatArray, ys: FloatArray, bold: Boolean, xh: Float): FloatArray {
    val n = xs.size
    val cum = FloatArray(n)
    for (i in 1 until n) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    val total = cum[n - 1]
    val gain = if (bold) BOLD_GAIN else 1f
    if (total < STAB_LENGTH * xh) return FloatArray(n) { min(1f, STAB_PRESSURE * gain) }
    return FloatArray(n) { i ->
        val s = cum[i] / total
        val rampIn = min(1f, 0.35f + 0.65f * (s / RAMP_IN))
        val ramp = if (s > 1f - RAMP_OUT) min(rampIn, 0.25f + 0.75f * ((1f - s) / RAMP_OUT)) else rampIn
        val a = max(i - 1, 0); val b = min(i + 1, n - 1)
        val len = hypot(xs[b] - xs[a], ys[b] - ys[a])
        val down = if (len > 0f) max(0f, (ys[b] - ys[a]) / len) else 0f // screen y grows downward
        min(1f, (BASE_PRESSURE + DOWNSTROKE_BONUS * down) * ramp * gain)
    }
}

/** Per-letter random transform, decided once so all strokes of a letter move together. */
private class LetterPose(val scale: Float, val shear: Float, val cosR: Float, val sinR: Float, val x: Float, val y: Float)

private fun letterPose(word: LaidWord, pc: PlacedChar, ci: Int, seed: Long, p: HumanParams): LetterPose {
    val key = word.index * 64 + ci
    val rot = degToRad(p.rotJitterDeg * gaussNoise(seed, key, 3))
    return LetterPose(
        scale = word.scale * pc.scale * (1f + p.scaleJitter * gaussNoise(seed, key, 1)),
        shear = tan(degToRad(p.slantDeg + p.slantJitterDeg * gaussNoise(seed, key, 2))),
        cosR = cos(rot), sinR = sin(rot),
        x = word.x + pc.dx + p.offsetJitter * word.scale * gaussNoise(seed, key, 4),
        y = word.baseline + p.offsetJitter * 1.2f * word.scale * gaussNoise(seed, key, 5),
    )
}

/** Glyph stroke points → letter-local pixels (scale, slant, small rotation about the letter origin). y is up. */
private fun localPoints(gs: GlyphStroke, pose: LetterPose): Pair<List<Float>, List<Float>> {
    val lx = ArrayList<Float>(gs.size); val ly = ArrayList<Float>(gs.size)
    for (i in 0 until gs.size) {
        val gx = gs.pts[i * 2] * pose.scale; val gy = gs.pts[i * 2 + 1] * pose.scale
        val sx = gx + gy * pose.shear                                    // shear keeps the baseline fixed
        lx += sx * pose.cosR - gy * pose.sinR; ly += sx * pose.sinR + gy * pose.cosR
    }
    return lx to ly
}

/** Where the next letter's pen starts, expressed in THIS letter's local frame (so a link can reach it). */
private fun linkTargetFor(word: LaidWord, ci: Int, pose: LetterPose, seed: Long, p: HumanParams): Pair<Float, Float>? {
    val here = word.chars[ci]
    val next = word.chars.getOrNull(ci + 1) ?: return null
    val nextFirst = next.glyph.strokes.firstOrNull { !it.delayed } ?: return null
    val gap = ((next.dx - here.dx) / word.scale) - here.glyph.adv * here.scale
    if (!p.joinLetters || !canJoin(here.ch, here.glyph, next.ch, next.glyph, gap)) return null
    val nextPose = letterPose(word, next, ci + 1, seed, p)
    val (nx, ny) = localPoints(nextFirst, nextPose).let { it.first.first() to it.second.first() }
    return (nextPose.x + nx - pose.x) to (pose.y - (nextPose.y - ny))
}

/** One glyph stroke → page-space [LaidStroke]: pose transform, densify, wobble, line wander, pressure. */
private fun strokeOf(
    gs: GlyphStroke, pose: LetterPose, word: LaidWord, ci: Int, si: Int, xHeight: Float, seed: Long, p: HumanParams,
    linkTo: Pair<Float, Float>? = null,
): LaidStroke {
    val xh = word.scale
    val key = word.index * 64 + ci
    val (glx, gly) = localPoints(gs, pose)
    val link = linkTo?.let { (tx, ty) -> linkCurve(glx.last(), gly.last(), tx, ty) }.orEmpty()
    val lx = glx + link.map { it.first } + listOfNotNull(linkTo?.first)
    val ly = gly + link.map { it.second } + listOfNotNull(linkTo?.second)
    val (dx, dy) = densify(lx.toFloatArray(), ly.toFloatArray(), xh * DENSE_GAP) // a straight `l` has only two points
    val xs = FloatArray(dx.size); val ys = FloatArray(dx.size)
    var run = 0f
    val wob = p.wobble * xh
    for (i in dx.indices) {
        if (i > 0) run += hypot(dx[i] - dx[i - 1], dy[i] - dy[i - 1])
        val wx = wob * smoothNoise(seed, key * 8 + si, run / (xh * WOBBLE_PERIOD))
        val wy = wob * smoothNoise(seed, key * 8 + si + 4, run / (xh * WOBBLE_PERIOD))
        xs[i] = pose.x + dx[i] + wx
        ys[i] = pose.y + lineWander(seed, word.line, xs[i], word.x, xHeight, p) - dy[i] + wy
    }
    return LaidStroke(word.page, xs, ys, pressureProfile(xs, ys, word.bold, xh), gs.delayed, word.index, ci)
}

/**
 * Turns a laid word into pen strokes in page coordinates: per-letter scale/slant/rotation/offset jitter, a slow line
 * wander and a low-frequency wobble along each stroke. Regular strokes first (in glyph order), delayed strokes
 * (i-dots, t-bars, accents) after the word. Pure and deterministic in [seed].
 */
fun wordStrokes(word: LaidWord, xHeight: Float, seed: Long, p: HumanParams): List<LaidStroke> {
    val regular = ArrayList<LaidStroke>(); val delayed = ArrayList<LaidStroke>()
    for ((ci, pc) in word.chars.withIndex()) {
        val pose = letterPose(word, pc, ci, seed, p)
        val lastRegular = pc.glyph.strokes.indexOfLast { !it.delayed }
        val link = linkTargetFor(word, ci, pose, seed, p)
        for ((si, gs) in pc.glyph.strokes.withIndex()) {
            val linkTo = if (si == lastRegular) link else null
            (if (gs.delayed) delayed else regular) += strokeOf(gs, pose, word, ci, si, xHeight, seed, p, linkTo)
        }
    }
    return regular + delayed
}

private const val PATH_KNOT_GAP = 3        // jitter is decided every few points and interpolated: a slow tremor, not noise
private const val PATH_MAX_GAP = 0.9f      // densify long straight paths to at most this many x-heights between points
private const val PATH_JITTER = 0.05f

/**
 * A hand-drawn version of a polyline (ruled lines, table grids, diagram strokes): densified, then nudged sideways by
 * a slow, smooth wobble so it never looks ruler-straight. End points stay put. Deterministic in [seed]/[salt].
 */
fun handPath(xs: FloatArray, ys: FloatArray, xh: Float, seed: Long, salt: Int, p: HumanParams): Pair<FloatArray, FloatArray> {
    if (xs.size < 2) return xs to ys
    val (dx, dy) = densify(xs, ys, xh * PATH_MAX_GAP)
    val n = dx.size
    val ox = FloatArray(n); val oy = FloatArray(n)
    for (i in 0 until n) {
        val a = max(i - 1, 0); val b = min(i + 1, n - 1)
        val len = hypot(dx[b] - dx[a], dy[b] - dy[a]).takeIf { it > 1e-4f } ?: 1f
        val nx = -(dy[b] - dy[a]) / len; val ny = (dx[b] - dx[a]) / len
        val edge = if (i == 0 || i == n - 1) 0f else 1f
        val k = i / PATH_KNOT_GAP; val f = (i % PATH_KNOT_GAP) / PATH_KNOT_GAP.toFloat()
        val j = unitNoise(seed, salt, k) * (1f - f) + unitNoise(seed, salt, k + 1) * f
        val amp = PATH_JITTER * xh * (if (p.wobble > 0f) 1f else 0f) * edge
        ox[i] = dx[i] + nx * j * amp; oy[i] = dy[i] + ny * j * amp
    }
    return ox to oy
}
