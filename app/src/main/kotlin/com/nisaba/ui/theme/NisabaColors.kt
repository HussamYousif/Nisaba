package com.nisaba.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class NisabaColors(
    val paper: Color = Color(0xFFF6F2EA),
    val ink: Color = Color(0xFF111111),
    val lapis: Color = Color(0xFF27407A),
    val ochre: Color = Color(0xFFB7892F),
    val faint: Color = Color(0xFF66625B), // ~5.5:1 on paper: secondary text must read with the frontlight off
)

val LocalNisabaColors = staticCompositionLocalOf { NisabaColors() }
