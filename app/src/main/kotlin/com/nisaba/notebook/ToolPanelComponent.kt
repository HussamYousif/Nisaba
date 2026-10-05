package com.nisaba.notebook

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nisaba.canvas.PEN_INKS
import com.nisaba.domain.ink.ShapeKind
import com.nisaba.domain.settings.PenSize
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaDialog
import com.nisaba.ui.theme.NisabaIcon
import com.nisaba.ui.theme.NisabaIconButton

private const val INKS_PER_ROW = 4
private const val PREVIEW_WIDTH = 2.2f // the sample line, px per page unit of pen width

/** The pen's inks and thicknesses (opened by tapping the pen while it is picked). Choices apply at once. */
@Composable
fun PenPanelComponent(state: NotebookUiState, vm: NotebookViewModel) {
    val c = Nisaba.colors
    NisabaDialog("Pen", vm::closePanel) {
        for (row in PEN_INKS.chunked(INKS_PER_ROW)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for ((name, ink) in row) {
                    val picked = ink.argb == state.penInk
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val frame = Modifier.size(72.dp).border(BorderStroke(if (picked) 4.dp else 1.5.dp, c.ink))
                        Box(
                            frame.padding(if (picked) 8.dp else 6.dp).background(Color(ink.argb))
                                .clickable { vm.setPenInk(ink.argb) }.semantics { contentDescription = name },
                        )
                        Text(name, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (pen in PenSize.entries) {
                val picked = pen == state.penSize
                Box(
                    Modifier.size(width = 104.dp, height = 64.dp).then(if (picked) Modifier.border(BorderStroke(4.dp, c.ink)) else Modifier)
                        .border(BorderStroke(1.5.dp, c.ink)).clickable { vm.setPenSize(pen) }.semantics { contentDescription = pen.name },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp)) {
                        val y = size.height / 2
                        val w = pen.width * PREVIEW_WIDTH
                        drawLine(Color(state.penInk), Offset(0f, y), Offset(size.width, y), w, cap = StrokeCap.Round)
                    }
                }
            }
        }
        NisabaButton("Done", vm::closePanel, Modifier.fillMaxWidth())
    }
}

private val SHAPES = listOf(
    Triple(ShapeKind.Auto, NisabaIcon.ShapeAuto, "Auto"),
    Triple(ShapeKind.Line, NisabaIcon.ShapeLine, "Line"),
    Triple(ShapeKind.Arrow, NisabaIcon.ShapeArrow, "Arrow"),
    Triple(ShapeKind.Rectangle, NisabaIcon.ShapeRectangle, "Box"),
    Triple(ShapeKind.Ellipse, NisabaIcon.ShapeEllipse, "Ellipse"),
    Triple(ShapeKind.Triangle, NisabaIcon.ShapeTriangle, "Triangle"),
)

/** What the shape tool draws. Auto: draw freely, rough shapes are cleaned up. The others: drag from corner to corner. */
@Composable
fun ShapePanelComponent(state: NotebookUiState, vm: NotebookViewModel) {
    NisabaDialog("Shapes", vm::closePanel) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((kind, icon, label) in SHAPES) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NisabaIconButton(icon, label, { vm.setShape(kind) }, selected = kind == state.shape)
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        val hint = if (state.shape == ShapeKind.Auto) {
            "Draw a line, box, circle or triangle; it comes out clean."
        } else {
            "Drag from one corner to the other."
        }
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = Nisaba.colors.faint)
    }
}
