package com.nisaba.ui.brand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.nisaba.scribe.hand.strokeOutline
import com.nisaba.scribe.layout.InkTone
import com.nisaba.scribe.motion.pointsReachedBy
import kotlinx.coroutines.delay

val LocalHandText = compositionLocalOf<HandTextCache?> { null }

private const val FRAME_MS = 50

private fun pathOf(poly: FloatArray) = Path().apply {
    if (poly.size < 4) return@apply
    moveTo(poly[0], poly[1])
    var i = 2
    while (i < poly.size) { lineTo(poly[i], poly[i + 1]); i += 2 }
    close()
}

private fun DrawScope.drawHand(text: HandText, color: Color, upToMs: Int?) {
    val k = size.width / text.width
    translate(0f, 0f) {
        scale(k, k, pivot = Offset.Zero) {
            translate(-text.minX, -text.minY) {
                for ((i, s) in text.strokes.withIndex()) {
                    val poly = if (upToMs == null || s.endMs <= upToMs) text.outlines[i]
                    else {
                        val n = pointsReachedBy(s, upToMs)
                        if (n < 1) continue
                        strokeOutline(s.points.slice(0, n))
                    }
                    drawPath(pathOf(poly), color)
                }
            }
        }
    }
}

/** Hand-written [text] drawn whole, each stroke in the pen [penFor] gives its tone: colour and width scale. */
@Composable
fun HandInkComponent(text: HandText, penFor: (InkTone) -> Pair<Color, Float>, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(text.width / text.height)) {
        val k = size.width / text.width
        scale(k, k, pivot = Offset.Zero) {
            translate(-text.minX, -text.minY) {
                for (s in text.strokes) {
                    val (color, width) = penFor(s.tone)
                    drawPath(pathOf(strokeOutline(s.points, widthScale = width)), color)
                }
            }
        }
    }
}

/**
 * Text written by the app's own hand. Static by default; with [animate] the letters are written stroke by stroke
 * at ~20 fps (discrete steps, e-ink friendly) and [onDone] fires at the end. [lineWidth] (page units) = where the hand
 * wraps; null = the cache's default, for short names.
 */
@Composable
fun HandTextComponent(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    animate: Boolean = false,
    onDone: () -> Unit = {},
    lineWidth: Float? = null,
) {
    val cache = LocalHandText.current ?: return
    val hand = remember(text, lineWidth) { if (lineWidth == null) cache.of(text) else cache.of(text, lineWidth) }
    var t by remember(text, animate) { mutableIntStateOf(if (animate) 0 else hand.durationMs) }
    LaunchedEffect(text, animate) {
        if (!animate) return@LaunchedEffect
        while (t < hand.durationMs) { delay(FRAME_MS.toLong()); t += FRAME_MS }
        onDone()
    }
    Canvas(modifier.aspectRatio(hand.width / hand.height)) { drawHand(hand, color, if (t >= hand.durationMs) null else t) }
}
