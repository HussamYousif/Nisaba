package com.nisaba.persona

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nisaba.domain.persona.Persona
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val LINE = 0.16f
private const val OWN_MIN_RAYS = 5
private const val OWN_RAY_KINDS = 4

/** A voice's sign: on its notebooks' covers and next to its name in the picker. [r] = the radius it fits in. */
fun DrawScope.drawSigil(voice: Persona, color: Color, c: Offset, r: Float) {
    val pen = Stroke(width = r * LINE, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (voice.id) {
        "book" -> openBook(color, c, r, pen)
        "teacher" -> cap(color, c, r, pen)
        "writer" -> nib(color, c, r, pen)
        "explorer" -> compass(color, c, r)
        "flirt" -> heart(color, c, r)
        "yukino" -> snowflake(color, c, r, pen)
        "zagreus" -> flame(color, c, r, pen)
        "plain" -> { drawCircle(color, r * 0.75f, c, style = pen); drawCircle(color, r * 0.18f, c) }
        else -> star(color, c, r, pen, OWN_MIN_RAYS + (voice.id.hashCode() and Int.MAX_VALUE) % OWN_RAY_KINDS)
    }
}

private fun path(pts: List<Offset>, close: Boolean = true) = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    for (p in pts.drop(1)) lineTo(p.x, p.y)
    if (close) close()
}

private fun DrawScope.openBook(color: Color, c: Offset, r: Float, pen: Stroke) {
    val spineTop = Offset(c.x, c.y - r * 0.45f)
    val spineBottom = Offset(c.x, c.y + r * 0.7f)
    for (side in listOf(-1f, 1f)) {
        val outerTop = Offset(c.x + side * r, c.y - r * 0.7f)
        val outerBottom = Offset(c.x + side * r, c.y + r * 0.45f)
        drawPath(path(listOf(spineTop, outerTop, outerBottom, spineBottom)), color, style = pen)
    }
}

private fun DrawScope.cap(color: Color, c: Offset, r: Float, pen: Stroke) {
    val board = listOf(
        Offset(c.x, c.y - r * 0.7f), Offset(c.x + r, c.y - r * 0.25f), Offset(c.x, c.y + r * 0.2f), Offset(c.x - r, c.y - r * 0.25f),
    )
    drawPath(path(board), color)
    val band = path(
        listOf(
            Offset(c.x - r * 0.55f, c.y), Offset(c.x - r * 0.55f, c.y + r * 0.55f),
            Offset(c.x + r * 0.55f, c.y + r * 0.55f), Offset(c.x + r * 0.55f, c.y),
        ),
        close = false,
    )
    drawPath(band, color, style = pen)
    drawLine(color, Offset(c.x + r * 0.85f, c.y - r * 0.2f), Offset(c.x + r * 0.85f, c.y + r * 0.6f), r * LINE, StrokeCap.Round)
}

private fun DrawScope.nib(color: Color, c: Offset, r: Float, pen: Stroke) {
    val tip = Offset(c.x, c.y + r)
    val outline = Path().apply {
        moveTo(tip.x, tip.y)
        quadraticTo(c.x - r * 0.75f, c.y, c.x - r * 0.35f, c.y - r * 0.85f)
        lineTo(c.x + r * 0.35f, c.y - r * 0.85f)
        quadraticTo(c.x + r * 0.75f, c.y, tip.x, tip.y)
        close()
    }
    drawPath(outline, color, style = pen)
    drawLine(color, tip, Offset(c.x, c.y - r * 0.1f), r * LINE * 0.8f, StrokeCap.Round)
    drawCircle(color, r * 0.13f, Offset(c.x, c.y - r * 0.2f))
}

private fun DrawScope.compass(color: Color, c: Offset, r: Float) {
    val inner = r * 0.24f
    val pts = (0 until 8).map { i ->
        val a = (PI / 4 * i - PI / 2).toFloat()
        val len = if (i % 2 == 0) r else inner
        Offset(c.x + cos(a) * len, c.y + sin(a) * len)
    }
    drawPath(path(pts), color)
}

private fun DrawScope.heart(color: Color, c: Offset, r: Float) {
    val p = Path().apply {
        moveTo(c.x, c.y + r * 0.85f)
        cubicTo(c.x - r * 1.3f, c.y - r * 0.05f, c.x - r * 0.6f, c.y - r * 1.05f, c.x, c.y - r * 0.4f)
        cubicTo(c.x + r * 0.6f, c.y - r * 1.05f, c.x + r * 1.3f, c.y - r * 0.05f, c.x, c.y + r * 0.85f)
        close()
    }
    drawPath(p, color)
}

private fun DrawScope.snowflake(color: Color, c: Offset, r: Float, pen: Stroke) {
    for (i in 0 until 6) {
        val a = (PI / 3 * i - PI / 2).toFloat()
        val end = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
        drawLine(color, c, end, pen.width, StrokeCap.Round)
        val mid = Offset(c.x + cos(a) * r * 0.55f, c.y + sin(a) * r * 0.55f)
        for (turn in listOf(-0.6f, 0.6f)) {
            val b = a + turn
            drawLine(color, mid, Offset(mid.x + cos(b) * r * 0.32f, mid.y + sin(b) * r * 0.32f), pen.width * 0.8f, StrokeCap.Round)
        }
    }
}

private fun DrawScope.flame(color: Color, c: Offset, r: Float, pen: Stroke) {
    fun tongue(scale: Float) = Path().apply {
        moveTo(c.x, c.y - r * scale)
        cubicTo(
            c.x + r * 0.2f * scale, c.y - r * 0.3f * scale, c.x + r * 0.85f * scale, c.y,
            c.x + r * 0.55f * scale, c.y + r * 0.6f * scale,
        )
        quadraticTo(c.x, c.y + r * 1.05f * scale, c.x - r * 0.55f * scale, c.y + r * 0.6f * scale)
        cubicTo(c.x - r * 0.85f * scale, c.y + r * 0.1f * scale, c.x - r * 0.3f * scale, c.y - r * 0.2f * scale, c.x, c.y - r * scale)
        close()
    }
    drawPath(tongue(1f), color, style = pen)
    drawPath(Path().apply { addPath(tongue(0.45f), Offset(0f, r * 0.35f)) }, color)
}

private fun DrawScope.star(color: Color, c: Offset, r: Float, pen: Stroke, rays: Int) {
    for (i in 0 until rays) {
        val a = (2 * PI / rays * i - PI / 2).toFloat()
        val from = Offset(c.x + cos(a) * r * 0.3f, c.y + sin(a) * r * 0.3f)
        drawLine(color, from, Offset(c.x + cos(a) * r, c.y + sin(a) * r), pen.width, StrokeCap.Round)
    }
    drawCircle(color, r * 0.2f, c)
}
