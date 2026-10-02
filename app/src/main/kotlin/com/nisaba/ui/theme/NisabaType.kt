package com.nisaba.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nisaba.R

// Titles and body: EB Garamond; labels: Inter (both OFL, bundled variable fonts).
@OptIn(ExperimentalTextApi::class)
private fun family(res: Int, vararg weights: FontWeight) = FontFamily(
    weights.map { w -> Font(res, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight))) },
)

private val Serif = family(R.font.eb_garamond, FontWeight.Normal, FontWeight.Medium)
private val Sans = family(R.font.inter, FontWeight.Normal, FontWeight.Medium)

val NisabaTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 34.sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 28.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 24.sp),
    titleMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 18.sp),
    bodyLarge = TextStyle(fontFamily = Serif, fontSize = 18.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 15.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontSize = 12.sp),
)
