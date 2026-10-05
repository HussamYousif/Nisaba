package com.nisaba.notebook

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.nisaba.domain.ink.StrokeOrigin
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageRect
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import kotlin.math.max
import kotlin.math.min

private const val MARGIN = 400f   // page units of empty canvas around everything written
private const val POINT_STEP = 3  // every third point is plenty at this scale

/** How page space maps onto the overview box. */
private class Fit(val area: PageRect, val scale: Float, val dx: Float, val dy: Float) {
    fun toBox(x: Float, y: Float) = Offset((x - area.left) * scale + dx, (y - area.top) * scale + dy)
    fun toPage(o: Offset) = PagePoint((o.x - dx) / scale + area.left, (o.y - dy) / scale + area.top)
}

private fun fit(area: PageRect, box: Size): Fit {
    val s = min(box.width / max(area.width, 1f), box.height / max(area.height, 1f))
    return Fit(area, s, (box.width - area.width * s) / 2f, (box.height - area.height * s) / 2f)
}

/**
 * The whole endless canvas at a glance: everything written, shrunk to fit, with the current view outlined. A tap
 * jumps there; Home goes back to where the canvas starts. One still picture (e-ink).
 */
@Composable
fun CanvasOverviewComponent(state: NotebookUiState, view: PageRect, onJump: (PagePoint) -> Unit, onHome: () -> Unit, onClose: () -> Unit) {
    val c = Nisaba.colors
    val area = remember(state.strokes, view) {
        val all = state.strokes.map { it.bounds } + view
        val l = all.minOf { it.left }; val t = all.minOf { it.top }; val r = all.maxOf { it.right }; val b = all.maxOf { it.bottom }
        PageRect(l - MARGIN, t - MARGIN, r + MARGIN, b + MARGIN)
    }
    Column(Modifier.fillMaxSize().background(c.paper).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("The whole canvas", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            NisabaButton("Home", onHome)
            NisabaButton("Close", onClose, filled = true)
        }
        Text("Tap a spot to go there.", style = MaterialTheme.typography.bodyLarge, color = c.faint)
        Canvas(
            Modifier.fillMaxWidth().weight(1f).border(BorderStroke(1.dp, c.ink)).pointerInput(area) {
                detectTapGestures { o -> onJump(fit(area, Size(size.width.toFloat(), size.height.toFloat())).toPage(o)) }
            },
        ) {
            val f = fit(area, size)
            val line = Stroke(width = 1.5.dp.toPx())
            for (s in state.strokes) {
                val p = s.points
                if (p.size == 0) continue
                val path = Path().apply {
                    val first = f.toBox(p.xs[0], p.ys[0]); moveTo(first.x, first.y)
                    var i = POINT_STEP
                    while (i < p.size) { val o = f.toBox(p.xs[i], p.ys[i]); lineTo(o.x, o.y); i += POINT_STEP }
                    val last = f.toBox(p.xs[p.size - 1], p.ys[p.size - 1]); lineTo(last.x, last.y)
                }
                drawPath(path, Color(s.color.argb), style = line)
            }
            val tl = f.toBox(view.left, view.top); val br = f.toBox(view.right, view.bottom)
            drawRect(c.lapis, tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(width = 2.dp.toPx()))
        }
    }
}
