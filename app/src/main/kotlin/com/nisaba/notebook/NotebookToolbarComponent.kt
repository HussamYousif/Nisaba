package com.nisaba.notebook

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nisaba.device.PenTool
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaDialog
import com.nisaba.ui.theme.NisabaIcon
import com.nisaba.ui.theme.NisabaIconButton

const val TOOLBAR_HEIGHT = 64
private val BLACK_INK = com.nisaba.domain.ink.InkColor.BLACK.argb

/** One entry of the ⋯ menu; the screen lists only what makes sense on this page. */
class MenuItem(val label: String, val onClick: () -> Unit)

/**
 * The notebook's top bar, in icons: back · title (tap to rename) · page n/m (tap for all pages) · pen (tap again for
 * colours and sizes) | shapes (tap again to pick one) | eraser (hold for area mode) · undo · redo · listening · ask now
 * (only when listening is off) · ⋯.
 */
@Composable
fun NotebookToolbarComponent(state: NotebookUiState, vm: NotebookViewModel, onBack: () -> Unit) {
    val c = Nisaba.colors
    Row(
        Modifier.fillMaxWidth().height(TOOLBAR_HEIGHT.dp).background(c.paper).border(BorderStroke(1.dp, c.ink)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NisabaIconButton(NisabaIcon.Back, "Back", onBack)
        Text(
            state.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).clickable { vm.beginRename() },
        )
        if (!state.endless) {
            val pageChip = Modifier.border(BorderStroke(1.dp, c.faint)).clickable { vm.openPageGrid() }
            Box(pageChip.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text("${state.pageIndex + 1} / ${state.pageCount} ▾", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(10.dp))
        }
        val erasing = state.tool.erases
        NisabaIconButton(
            NisabaIcon.Pen, "Pen (tap again for colours)", vm::tapPen, selected = state.tool == PenTool.Pen,
            dot = Color(state.penInk).takeIf { state.penInk != BLACK_INK },
        )
        NisabaIconButton(NisabaIcon.Shape, "Shapes (tap again to pick)", vm::tapShape, selected = state.tool == PenTool.Shape)
        NisabaIconButton(
            if (state.tool == PenTool.AreaEraser) NisabaIcon.AreaEraser else NisabaIcon.Eraser,
            "Eraser (hold for area eraser)",
            { if (!erasing) vm.setTool(PenTool.StrokeEraser) },
            selected = erasing,
            onLongClick = { vm.setTool(if (state.tool == PenTool.AreaEraser) PenTool.StrokeEraser else PenTool.AreaEraser) },
        )
        Spacer(Modifier.width(10.dp))
        NisabaIconButton(NisabaIcon.Undo, "Undo", { vm.undo() }, enabled = state.canUndo)
        NisabaIconButton(NisabaIcon.Redo, "Redo", { vm.redo() }, enabled = state.canRedo)
        if (state.aiAvailable) {
            Spacer(Modifier.width(10.dp))
            NisabaIconButton(
                if (state.listening) NisabaIcon.Listening else NisabaIcon.Muted,
                if (state.listening) "Listening (tap to mute)" else "Muted (tap to listen)",
                vm::toggleListening, selected = state.listening,
            )
            if (!state.listening) NisabaIconButton(NisabaIcon.AskNow, "Ask now", vm::askNow, enabled = state.askable)
        }
        Spacer(Modifier.width(10.dp))
        NisabaIconButton(NisabaIcon.More, "More", vm::openMenu)
    }
}

/** The ⋯ menu, drawn over the page (it outlives the bar hiding). */
@Composable
fun NotebookMenuComponent(title: String, menu: List<MenuItem>, onClose: () -> Unit) {
    NisabaDialog(title.ifBlank { "Notebook" }, onClose) {
        for (item in menu) NisabaButton(item.label, { onClose(); item.onClick() }, Modifier.fillMaxWidth())
    }
}

private const val GRID_COLUMNS = 6
private const val GRID_ROWS = 6

/** Every page as a number, a screenful at a time (no scrolling on e-ink); a tap opens it. */
@Composable
fun PageGridComponent(state: NotebookUiState, vm: NotebookViewModel) {
    val perScreen = GRID_COLUMNS * GRID_ROWS
    val screen = state.pageGridScreen
    val first = screen * perScreen
    NisabaDialog("Pages", vm::closePageGrid) {
        for (r in 0 until GRID_ROWS) {
            val start = first + r * GRID_COLUMNS
            if (start >= state.pageCount) break
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in start until minOf(start + GRID_COLUMNS, state.pageCount)) {
                    val cell = Modifier.size(width = 64.dp, height = 48.dp)
                    NisabaButton("${i + 1}", { vm.goToPage(i) }, cell, filled = i == state.pageIndex)
                }
            }
        }
        val screens = (state.pageCount + perScreen - 1) / perScreen
        if (screens > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                NisabaButton("‹ Earlier", { vm.pageGridScreen(screen - 1) }, enabled = screen > 0)
                Text("${screen + 1} of $screens", style = MaterialTheme.typography.labelLarge)
                NisabaButton("Later ›", { vm.pageGridScreen(screen + 1) }, enabled = screen < screens - 1)
            }
        }
    }
}
