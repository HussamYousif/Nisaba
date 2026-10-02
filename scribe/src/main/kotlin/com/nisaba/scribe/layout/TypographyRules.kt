package com.nisaba.scribe.layout

private fun table(vararg groups: Pair<String, String>): Map<Char, String> =
    groups.flatMap { (chars, replacement) -> chars.map { it to replacement } }.toMap()

private val REPLACEMENTS: Map<Char, String> = table(
    "—–‒−‐‑" to "-",
    "‘’‚′`´" to "'",
    "“”„″«»" to "\"",
    "…" to "...",
    "•▪●◦⁃" to "-",
    "·‧⋅" to ".",
    "       " to " ",
    "​‌‍﻿" to "",
    "→" to "->", "←" to "<-", "×" to "x", "÷" to "/",
    "≈" to "~", "≤" to "<=", "≥" to ">=", "≠" to "!=", "°" to "deg",
)

/**
 * Models write typographic characters (— “ ” ’ … •) that a pen font has no glyph for. Map them to what a person would
 * actually write by hand; anything still unknown later becomes a visible `?`.
 */
fun normalizeTypography(text: String): String {
    val sb = StringBuilder(text.length)
    for (c in text) sb.append(REPLACEMENTS[c] ?: c.toString())
    return sb.toString()
}
