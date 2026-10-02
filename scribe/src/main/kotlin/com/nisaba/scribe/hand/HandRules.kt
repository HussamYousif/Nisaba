package com.nisaba.scribe.hand

import com.nisaba.domain.ink.InkPoints
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

private const val RESAMPLE_STEP = 2.5f
private const val MAX_SUBDIVISIONS = 12
private const val CAP_STEPS = 6
private const val DOT_STEPS = 14

fun strokeWidthAt(pressure: Float, profile: PenProfile): Float =
    profile.baseWidth + profile.pressureGain * pressure.coerceIn(0f, 1f).pow(profile.pressureExp)

/** Points along a stroke after Catmull-Rom smoothing: parallel x, y, pressure arrays. */
class SmoothedStroke(val xs: FloatArray, val ys: FloatArray, val ps: FloatArray) {
    val size: Int get() = xs.size
}

/** Centripetal-ish (uniform) Catmull-Rom resample at roughly [step] spacing; never allocates per point. */
fun smoothStroke(p: InkPoints, step: Float = RESAMPLE_STEP): SmoothedStroke {
    if (p.size < 3) return SmoothedStroke(p.xs.copyOf(), p.ys.copyOf(), p.ps.copyOf())
    val ox = ArrayList<Float>(p.size * 2); val oy = ArrayList<Float>(p.size * 2); val op = ArrayList<Float>(p.size * 2)
    fun cr(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val t2 = t * t; val t3 = t2 * t
        return 0.5f * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
    }
    for (i in 0 until p.size - 1) {
        val i0 = maxOf(i - 1, 0); val i3 = minOf(i + 2, p.size - 1)
        val n = ceil(hypot(p.xs[i + 1] - p.xs[i], p.ys[i + 1] - p.ys[i]) / step).toInt().coerceIn(1, MAX_SUBDIVISIONS)
        for (k in 0 until n) {
            val t = k / n.toFloat()
            ox += cr(p.xs[i0], p.xs[i], p.xs[i + 1], p.xs[i3], t)
            oy += cr(p.ys[i0], p.ys[i], p.ys[i + 1], p.ys[i3], t)
            op += p.ps[i] + (p.ps[i + 1] - p.ps[i]) * t
        }
    }
    ox += p.xs.last(); oy += p.ys.last(); op += p.ps.last()
    return SmoothedStroke(ox.toFloatArray(), oy.toFloatArray(), op.toFloatArray())
}

/**
 * Closed polygon (x0,y0,x1,y1,…) outlining a variable-width stroke with round caps.
 * Same function renders on-device ink and the `scribe-preview` tool, so the preview matches the panel.
 */
fun strokeOutline(points: InkPoints, profile: PenProfile = PenProfile.DEFAULT, widthScale: Float = 1f): FloatArray {
    if (points.size == 0) return FloatArray(0)
    val s = smoothStroke(points)
    val n = s.size
    val half = FloatArray(n) { strokeWidthAt(s.ps[it], profile) * widthScale / 2f }
    if (n == 1 || (n == 2 && hypot(s.xs[1] - s.xs[0], s.ys[1] - s.ys[0]) < 0.01f)) return circle(s.xs[0], s.ys[0], half[0])

    val nx = FloatArray(n); val ny = FloatArray(n)
    for (i in 0 until n) {
        val a = maxOf(i - 1, 0); val b = minOf(i + 1, n - 1)
        var dx = s.xs[b] - s.xs[a]; var dy = s.ys[b] - s.ys[a]
        val len = hypot(dx, dy)
        if (len < 1e-4f) { dx = 1f; dy = 0f } else { dx /= len; dy /= len }
        nx[i] = -dy; ny[i] = dx
    }
    val out = ArrayList<Float>((n * 2 + CAP_STEPS * 2) * 2)
    fun add(x: Float, y: Float) { out += x; out += y }
    for (i in 0 until n) add(s.xs[i] + nx[i] * half[i], s.ys[i] + ny[i] * half[i])
    cap(out, s.xs[n - 1], s.ys[n - 1], nx[n - 1], ny[n - 1], half[n - 1])
    for (i in n - 1 downTo 0) add(s.xs[i] - nx[i] * half[i], s.ys[i] - ny[i] * half[i])
    cap(out, s.xs[0], s.ys[0], -nx[0], -ny[0], half[0])
    return out.toFloatArray()
}

/** Half-circle from the +normal side to the -normal side around the tip, bulging along the stroke direction. */
private fun cap(out: MutableList<Float>, cx: Float, cy: Float, nx: Float, ny: Float, r: Float) {
    for (k in 1 until CAP_STEPS) {
        val a = PI.toFloat() * k / CAP_STEPS
        // rotate the normal from +n to -n through the forward direction (tangent = (ny, -nx))
        val c = cos(a); val sn = sin(a)
        out += cx + (nx * c + ny * sn) * r
        out += cy + (ny * c - nx * sn) * r
    }
}

private fun circle(cx: Float, cy: Float, r: Float): FloatArray {
    val out = FloatArray(DOT_STEPS * 2)
    for (k in 0 until DOT_STEPS) {
        val a = (2 * PI * k / DOT_STEPS).toFloat()
        out[k * 2] = cx + cos(a) * r; out[k * 2 + 1] = cy + sin(a) * r
    }
    return out
}
