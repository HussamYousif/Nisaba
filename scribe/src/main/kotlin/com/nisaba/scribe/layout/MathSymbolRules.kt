@file:Suppress("MaxLineLength") // coordinate tables: one symbol per row reads best

package com.nisaba.scribe.layout

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Pen paths for one math symbol in x-height units (baseline y=0, y up) with its advance width. */
class SymbolPaths(val adv: Float, val paths: List<FloatArray>)

private fun p(vararg v: Float) = floatArrayOf(*v)

private fun dot(x: Float, y: Float, r: Float = 0.05f) = p(x - r, y, x, y + r, x + r, y, x, y - r, x - r, y)

private fun wave(y: Float, x0: Float = 0.05f, x1: Float = 0.8f): FloatArray {
    val n = 9
    val out = FloatArray(n * 2)
    for (i in 0 until n) { val t = i / (n - 1f); out[i * 2] = x0 + (x1 - x0) * t; out[i * 2 + 1] = y + 0.09f * sin(t * 2f * PI.toFloat()) }
    return out
}

private fun lemniscate(): FloatArray {
    val n = 33
    val out = FloatArray(n * 2)
    for (i in 0 until n) {
        val t = 2f * PI.toFloat() * i / (n - 1f)
        val d = 1f + sin(t) * sin(t)
        out[i * 2] = 0.5f + 0.48f * cos(t) / d
        out[i * 2 + 1] = 0.42f + 0.62f * sin(t) * cos(t) / d
    }
    return out
}

private val SYMBOLS: Map<String, SymbolPaths> = mapOf(
    "sum" to SymbolPaths(0.95f, listOf(p(0.88f, 1.0f, 0.05f, 1.0f, 0.5f, 0.5f, 0.05f, 0.0f, 0.88f, 0.0f))),
    "prod" to SymbolPaths(0.95f, listOf(p(0.05f, 1.0f, 0.88f, 1.0f), p(0.2f, 1.0f, 0.2f, 0.0f), p(0.73f, 1.0f, 0.73f, 0.0f))),
    "int" to SymbolPaths(0.6f, listOf(p(0.55f, 1.08f, 0.45f, 1.14f, 0.36f, 1.05f, 0.31f, 0.8f, 0.29f, 0.5f, 0.27f, 0.2f, 0.22f, -0.06f, 0.12f, -0.14f, 0.03f, -0.06f))),
    "infty" to SymbolPaths(1.05f, listOf(lemniscate())),
    "pm" to SymbolPaths(0.85f, listOf(p(0.05f, 0.7f, 0.75f, 0.7f), p(0.4f, 1.0f, 0.4f, 0.4f), p(0.05f, 0.1f, 0.75f, 0.1f))),
    "mp" to SymbolPaths(0.85f, listOf(p(0.05f, 0.9f, 0.75f, 0.9f), p(0.05f, 0.4f, 0.75f, 0.4f), p(0.4f, 0.7f, 0.4f, 0.1f))),
    "times" to SymbolPaths(0.8f, listOf(p(0.1f, 0.15f, 0.7f, 0.75f), p(0.1f, 0.75f, 0.7f, 0.15f))),
    "div" to SymbolPaths(0.85f, listOf(p(0.05f, 0.45f, 0.75f, 0.45f), dot(0.4f, 0.78f), dot(0.4f, 0.12f))),
    "cdot" to SymbolPaths(0.4f, listOf(dot(0.15f, 0.45f, 0.06f))),
    "le" to SymbolPaths(0.85f, listOf(p(0.72f, 0.95f, 0.1f, 0.65f, 0.72f, 0.35f), p(0.1f, 0.1f, 0.72f, 0.1f))),
    "ge" to SymbolPaths(0.85f, listOf(p(0.1f, 0.95f, 0.72f, 0.65f, 0.1f, 0.35f), p(0.1f, 0.1f, 0.72f, 0.1f))),
    "ne" to SymbolPaths(0.85f, listOf(p(0.05f, 0.35f, 0.75f, 0.35f), p(0.05f, 0.65f, 0.75f, 0.65f), p(0.6f, 0.95f, 0.2f, 0.05f))),
    "approx" to SymbolPaths(0.9f, listOf(wave(0.7f), wave(0.3f))),
    "equiv" to SymbolPaths(0.85f, listOf(p(0.05f, 0.2f, 0.75f, 0.2f), p(0.05f, 0.45f, 0.75f, 0.45f), p(0.05f, 0.7f, 0.75f, 0.7f))),
    "sim" to SymbolPaths(0.9f, listOf(wave(0.5f))),
    "to" to SymbolPaths(1.1f, listOf(p(0.05f, 0.45f, 0.98f, 0.45f), p(0.72f, 0.72f, 0.98f, 0.45f, 0.72f, 0.18f))),
    "leftarrow" to SymbolPaths(1.1f, listOf(p(0.98f, 0.45f, 0.05f, 0.45f), p(0.31f, 0.72f, 0.05f, 0.45f, 0.31f, 0.18f))),
    "implies" to SymbolPaths(1.1f, listOf(p(0.05f, 0.33f, 0.9f, 0.33f), p(0.05f, 0.6f, 0.9f, 0.6f), p(0.68f, 0.85f, 0.98f, 0.46f, 0.68f, 0.08f))),
    "ldots" to SymbolPaths(1.0f, listOf(dot(0.12f, 0.05f, 0.05f), dot(0.5f, 0.05f, 0.05f), dot(0.88f, 0.05f, 0.05f))),
    "degree" to SymbolPaths(0.4f, listOf(FloatArray(26) { i -> if (i % 2 == 0) 0.2f + 0.14f * cos(i / 2 * 2f * PI.toFloat() / 12f) else 0.85f + 0.14f * sin((i - 1) / 2 * 2f * PI.toFloat() / 12f) })),
    "partial" to SymbolPaths(0.6f, listOf(p(0.45f, 0.62f, 0.3f, 0.75f, 0.12f, 0.6f, 0.1f, 0.3f, 0.25f, 0.05f, 0.45f, 0.2f, 0.45f, 0.62f, 0.5f, 0.9f, 0.3f, 1.05f))),
    "nabla" to SymbolPaths(0.85f, listOf(p(0.05f, 1.0f, 0.8f, 1.0f, 0.42f, 0.0f, 0.05f, 1.0f))),
    "in" to SymbolPaths(0.75f, listOf(p(0.65f, 0.95f, 0.3f, 0.95f, 0.15f, 0.7f, 0.15f, 0.3f, 0.3f, 0.05f, 0.65f, 0.05f), p(0.15f, 0.5f, 0.6f, 0.5f))),
    "forall" to SymbolPaths(0.8f, listOf(p(0.05f, 1.0f, 0.4f, 0.0f, 0.75f, 1.0f), p(0.2f, 0.55f, 0.6f, 0.55f))),
    "exists" to SymbolPaths(0.7f, listOf(p(0.1f, 1.0f, 0.6f, 1.0f, 0.6f, 0.0f, 0.1f, 0.0f), p(0.6f, 0.5f, 0.15f, 0.5f))),
    "angle" to SymbolPaths(0.8f, listOf(p(0.75f, 0.0f, 0.05f, 0.0f, 0.65f, 0.75f))),
    "perp" to SymbolPaths(0.8f, listOf(p(0.05f, 0.0f, 0.75f, 0.0f), p(0.4f, 0.0f, 0.4f, 0.95f))),
    "propto" to SymbolPaths(0.9f, listOf(p(0.75f, 0.85f, 0.4f, 0.1f, 0.2f, 0.4f, 0.4f, 0.85f, 0.75f, 0.1f))),
    "subset" to SymbolPaths(0.75f, listOf(p(0.65f, 0.95f, 0.3f, 0.95f, 0.15f, 0.7f, 0.15f, 0.3f, 0.3f, 0.05f, 0.65f, 0.05f))),
)

/** Names that behave like binary operators (extra space around them in a row). */
val BINARY_OPERATORS: Set<String> = setOf("+", "-", "=", "<", ">", "le", "ge", "ne", "approx", "to", "times", "pm", "mp", "div", "equiv", "implies", "leftarrow", "sim", "in", "subset", "propto")

fun symbolPaths(name: String): SymbolPaths? = SYMBOLS[name]

/** A stretchy delimiter spanning [bottom]..[top] (x-height units); `left` picks the opening or closing form. */
fun delimiterPaths(delimiter: String, left: Boolean, top: Float, bottom: Float, width: Float): List<FloatArray> {
    val mid = (top + bottom) / 2f
    val w = width
    fun x(v: Float) = if (left) w - v else v // mirror for the closing form
    return when (delimiter) {
        "(", ")" -> listOf(p(x(0.75f * w), top, x(0.25f * w), top * 0.5f + mid * 0.5f, x(0.1f * w), mid, x(0.25f * w), bottom * 0.5f + mid * 0.5f, x(0.75f * w), bottom))
        "[", "]" -> listOf(p(x(0.8f * w), top, x(0.2f * w), top, x(0.2f * w), bottom, x(0.8f * w), bottom))
        "{", "}" -> listOf(p(x(0.8f * w), top, x(0.45f * w), top, x(0.45f * w), mid + 0.15f, x(0.15f * w), mid, x(0.45f * w), mid - 0.15f, x(0.45f * w), bottom, x(0.8f * w), bottom))
        "|", "||" -> listOf(p(w / 2f, top, w / 2f, bottom))
        "<", ">" -> listOf(p(x(0.8f * w), top, x(0.2f * w), mid, x(0.8f * w), bottom))
        else -> emptyList()
    }
}
