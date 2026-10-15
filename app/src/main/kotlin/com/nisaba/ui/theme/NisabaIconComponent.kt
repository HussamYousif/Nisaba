package com.nisaba.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The toolbar's glyphs, drawn as monoline ink like the rest of the brand (24-unit grid). */
enum class NisabaIcon {
    Back, Pen, Shape, Eraser, AreaEraser, Undo, Redo, Listening, Muted, AskNow, More,
    ShapeAuto, ShapeLine, ShapeArrow, ShapeRectangle, ShapeEllipse, ShapeTriangle,
}

private const val GRID = 24f

/**
 * A square icon button. [selected] inverts it (filled ink, paper glyph); [onLongClick] is optional (e.g. the eraser's
 * area mode); [dot] marks a corner with a colour (the pen's ink). No ripple: e-ink.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NisabaIconButton(
    icon: NisabaIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    dot: Color? = null,
) {
    val c = Nisaba.colors
    val ink = when { selected -> c.paper; enabled -> c.ink; else -> c.faint }
    Box(
        modifier
            .size(48.dp)
            .then(if (selected) Modifier.background(c.ink) else Modifier)
            .border(BorderStroke(1.5.dp, if (enabled) c.ink else c.faint))
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(36.dp)) { drawIcon(icon, ink) }
        if (dot != null) {
            Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(13.dp).background(c.paper).padding(2.dp).background(dot))
        }
    }
}

/** Just the drawn glyph of [icon], without a button (a badge, a status mark). */
@Composable
fun NisabaIconGlyph(icon: NisabaIcon, color: Color, modifier: Modifier = Modifier) = Canvas(modifier) { drawIcon(icon, color) }

private fun DrawScope.drawIcon(icon: NisabaIcon, ink: Color) {
    val k = size.minDimension / GRID
    val line = Stroke(width = 1.8f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun path(build: Path.() -> Unit) = drawPath(Path().apply(build), ink, style = line)
    fun Path.m(x: Float, y: Float) = moveTo(x * k, y * k)
    fun Path.l(x: Float, y: Float) = lineTo(x * k, y * k)
    fun Path.q(x1: Float, y1: Float, x: Float, y: Float) = quadraticTo(x1 * k, y1 * k, x * k, y * k)
    fun dot(x: Float, y: Float, r: Float = 1.6f) = drawCircle(ink, r * k, androidx.compose.ui.geometry.Offset(x * k, y * k))
    when (icon) {
        NisabaIcon.Back -> path { m(15f, 4f); l(7f, 12f); l(15f, 20f) }
        NisabaIcon.Pen -> {                                   // a reed-pen nib with its slit and breather hole
            path { m(6f, 10f); q(12f, 1f, 18f, 10f); l(12f, 22f); l(6f, 10f) }
            path { m(12f, 13.5f); l(12f, 22f) }
            dot(12f, 12f, 1.3f)
        }
        NisabaIcon.Eraser, NisabaIcon.AreaEraser -> {
            path { m(4f, 15f); l(13f, 6f); l(20f, 13f); l(11f, 22f); l(4f, 15f) }
            path { m(8.5f, 10.5f); l(15.5f, 17.5f) }
            if (icon == NisabaIcon.AreaEraser) {
                drawRect(
                    ink, androidx.compose.ui.geometry.Offset(1f * k, 1f * k), androidx.compose.ui.geometry.Size(22f * k, 22f * k),
                    style = Stroke(width = 1.2f * k, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f * k, 2.5f * k))),
                )
            }
        }
        NisabaIcon.Undo -> path { m(9f, 6f); l(4f, 11f); l(9f, 16f); m(4f, 11f); l(14f, 11f); q(20f, 11f, 20f, 17f); l(20f, 19f) }
        NisabaIcon.Redo -> path { m(15f, 6f); l(20f, 11f); l(15f, 16f); m(20f, 11f); l(10f, 11f); q(4f, 11f, 4f, 17f); l(4f, 19f) }
        NisabaIcon.Listening -> {                             // an open eye: Nisaba is reading the page
            path { m(2f, 12f); q(12f, 2.5f, 22f, 12f); q(12f, 21.5f, 2f, 12f) }
            drawCircle(ink, 3.2f * k, androidx.compose.ui.geometry.Offset(12f * k, 12f * k), style = line)
            dot(12f, 12f, 1.2f)
        }
        NisabaIcon.Muted -> {                                 // closed, lashes down
            path { m(2f, 11f); q(12f, 19f, 22f, 11f) }
            path { m(6f, 14.5f); l(4.5f, 17.5f); m(12f, 15.5f); l(12f, 19f); m(18f, 14.5f); l(19.5f, 17.5f) }
        }
        NisabaIcon.AskNow -> {                                // a quill and the line it writes
            path { m(21f, 3f); q(9f, 5f, 6f, 17f); q(15f, 14f, 21f, 3f) }
            path { m(6f, 17f); l(3f, 21f); m(9f, 21f); l(21f, 21f) }
        }
        NisabaIcon.More -> { dot(5f, 12f); dot(12f, 12f); dot(19f, 12f) }
        else -> drawShapeIcon(icon, ink, line)
    }
}

/** The shape tool and the shapes it draws. */
private fun DrawScope.drawShapeIcon(icon: NisabaIcon, ink: Color, line: Stroke) {
    val k = size.minDimension / GRID
    fun path(build: Path.() -> Unit) = drawPath(Path().apply(build), ink, style = line)
    fun Path.m(x: Float, y: Float) = moveTo(x * k, y * k)
    fun Path.l(x: Float, y: Float) = lineTo(x * k, y * k)
    fun Path.q(x1: Float, y1: Float, x: Float, y: Float) = quadraticTo(x1 * k, y1 * k, x * k, y * k)
    when (icon) {
        NisabaIcon.Shape -> {                                 // a square behind a circle
            path { m(3f, 9f); l(14f, 9f); l(14f, 20f); l(3f, 20f); l(3f, 9f) }
            drawCircle(ink, 5.5f * k, androidx.compose.ui.geometry.Offset(15.5f * k, 8.5f * k), style = line)
        }
        NisabaIcon.ShapeAuto -> {                             // a wobbly loop and its clean twin
            path { m(5f, 13f); q(4f, 5f, 11f, 5f); q(19f, 6f, 18f, 12f); q(17f, 19f, 10f, 19f); q(5f, 18f, 6f, 12f) }
            path { m(18f, 3f); l(18f, 7f); m(16f, 5f); l(20f, 5f) }
        }
        NisabaIcon.ShapeLine -> path { m(4f, 20f); l(20f, 4f) }
        NisabaIcon.ShapeArrow -> path { m(4f, 20f); l(20f, 4f); m(11f, 4f); l(20f, 4f); l(20f, 13f) }
        NisabaIcon.ShapeRectangle -> path { m(3f, 6f); l(21f, 6f); l(21f, 18f); l(3f, 18f); l(3f, 6f) }
        NisabaIcon.ShapeEllipse -> drawOval(
            ink, androidx.compose.ui.geometry.Offset(2.5f * k, 5.5f * k), androidx.compose.ui.geometry.Size(19f * k, 13f * k), style = line,
        )
        NisabaIcon.ShapeTriangle -> path { m(12f, 4f); l(21f, 20f); l(3f, 20f); l(12f, 4f) }
        else -> Unit
    }
}
