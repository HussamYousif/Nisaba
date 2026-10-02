package com.nisaba.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Seeded cuneiform-wedge rosette: every notebook gets its own cover from its seed. */
fun DrawScope.drawCover(seed: Int, ink: Color, accent: Color) {
    val rnd = Random(seed)
    val wedges = 5 + rnd.nextInt(5)
    val rot = rnd.nextFloat() * 360f
    val r = size.minDimension * 0.34f
    val c = Offset(size.width / 2f, size.height * 0.40f)
    val long = rnd.nextInt(wedges)
    rotate(rot, c) {
        for (i in 0 until wedges) {
            val a = (2 * Math.PI * i / wedges).toFloat()
            val len = if (i == long) r * 1.35f else r
            val dx = cos(a); val dy = sin(a)
            val nx = -dy; val ny = dx
            val base = r * 0.16f
            val tip = Offset(c.x + dx * len, c.y + dy * len)
            val b1 = Offset(c.x + dx * r * 0.18f + nx * base, c.y + dy * r * 0.18f + ny * base)
            val b2 = Offset(c.x + dx * r * 0.18f - nx * base, c.y + dy * r * 0.18f - ny * base)
            val p = Path().apply { moveTo(tip.x, tip.y); lineTo(b1.x, b1.y); lineTo(b2.x, b2.y); close() }
            drawPath(p, if (i == long) accent else ink)
        }
        drawCircle(ink, r * 0.07f, c)
    }
    drawRect(
        ink, topLeft = Offset(size.width * 0.12f, size.height * 0.80f),
        size = androidx.compose.ui.geometry.Size(size.width * 0.76f, 2f),
    )
    drawRect(ink, topLeft = Offset(0f, 0f), size = size, style = Stroke(width = 3f))
}
