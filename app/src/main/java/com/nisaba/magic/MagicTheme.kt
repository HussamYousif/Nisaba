package com.nisaba.magic

import android.graphics.Color

/**
 * The parchment world palette (docs/09 §1).
 * Values mirror res/values/colors.xml so views can use them without lookups.
 */
object MagicTheme {
    val PARCHMENT: Int = Color.rgb(0xF3, 0xEA, 0xD8)
    val PARCHMENT_DEEP: Int = Color.rgb(0xE9, 0xDC, 0xC3)
    val WOOD: Int = Color.rgb(0x24, 0x1F, 0x18)
    val INK: Int = Color.rgb(0x3A, 0x2E, 0x25)
    val INK_SOFT: Int = Color.rgb(0x6B, 0x5A, 0x45)
    val ACCENT_RED: Int = Color.rgb(0x7A, 0x2E, 0x1D)
    val BLUE_INK: Int = Color.rgb(0x2C, 0x3E, 0x66)

    /** Faint ruled lines drawn on blank pages (page px). */
    const val RULE_SPACING = 80f
    const val RULE_ALPHA = 0x0E

    /** Map a stored ink-color key to an ARGB int. */
    fun inkColor(key: String): Int = when (key) {
        "ink" -> INK
        "blue" -> BLUE_INK
        else -> INK // "sepia" default
    }
}
