package com.nisaba.notebook

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.nisaba.domain.ink.StrokeOrigin
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
 * colours and sizes) | shapes (tap again to pick one) | eraser (hold for area mode) | lasso · undo · redo · listening · ask now
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
        Box( // the whole height of the bar renames, not just the line of text
            Modifier.weight(1f).fillMaxHeight().clickable { vm.beginRename() }.padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) { Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (!state.endless) {
            val pageChip = Modifier.border(BorderStroke(1.dp, c.faint)).clickable { vm.openPageGrid() }
            Box(pageChip.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text("${state.pageLabel} ▾", style = MaterialTheme.typography.labelLarge)
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
        NisabaIconButton(NisabaIcon.Lasso, "Lasso (loop ink to move, colour, check or ask about it)", { vm.setTool(PenTool.Lasso) },
            selected = state.tool == PenTool.Lasso)
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

/** The ⋯ menu for this page. */
internal fun notebookMenu(
    state: NotebookUiState,
    voice: String,
    vm: NotebookViewModel,
    onOpenSettings: () -> Unit,
): List<MenuItem> = buildList {
    addAll(bookItems(state, vm)) // a book walk: the way on first
    if (!state.endless) add(MenuItem("+ Page after this one", vm::insertBlankPage))
    val ownInk = state.aiAvailable && state.strokes.any { it.origin is StrokeOrigin.User }
    if (ownInk) add(MenuItem("Check my work (✓ or ✗ beside each line)", vm::checkWork))
    if (!state.endless && ownInk) add(MenuItem("Tidy this page (a clean copy on a new page)", vm::tidyPage))
    if (state.endless) { add(MenuItem("Overview", vm::showOverview)); add(MenuItem("Home", vm::goHome)) }
    if (state.lesson) add(MenuItem("Lesson progress", vm::showProgress))
    val due = state.dueReviews()
    if (state.lesson && due > 0) add(MenuItem("Review what's due ($due)", vm::reviewDue))
    if (state.aiAvailable && !state.endless) {
        add(MenuItem("Quiz me on this notebook", vm::quizMe))
        add(MenuItem("Today's page", vm::todaysPage))
        add(MenuItem("This week, recapped", vm::recapWeek))
    }
    add(MenuItem(if (state.isPrivate) "Private: on (tap to turn off)" else "Make private (nothing remembered)", vm::togglePrivate))
    if (state.aiAvailable) add(MenuItem("Voice: $voice", vm::openVoice))
    if (!state.endless) add(MenuItem("Paper: ${state.paper.name.lowercase()}…", vm::openPaper))
    if (state.aiAvailable && state.hasDocument) {
        add(MenuItem(if (state.lesson) "Stop tutoring" else "Tutor me on this PDF", vm::toggleTutor))
    }
    add(MenuItem("Export as PDF…", { vm.export(asPdf = true) }))
    add(MenuItem("Export as text…", { vm.export(asPdf = false) }))
    add(MenuItem("Rename", vm::beginRename))
    add(MenuItem("Settings", onOpenSettings))
}

/** In a book walk: the next page of the walk, and its map. */
private fun bookItems(state: NotebookUiState, vm: NotebookViewModel): List<MenuItem> {
    val book = state.book?.takeIf { it.ready } ?: return emptyList()
    return listOfNotNull(
        book.next?.let { MenuItem(nextLabel(book, it), vm::nextBookStep) },
        MenuItem("The map of the book (${book.walked} of ${book.concepts.size})", vm::openBookMap),
    )
}

/** The ⋯ menu, drawn over the page (it outlives the bar hiding). */
@Composable
fun NotebookMenuComponent(title: String, menu: List<MenuItem>, onClose: () -> Unit) {
    NisabaDialog(title.ifBlank { "Notebook" }, onClose) {
        for (item in menu) NisabaButton(item.label, { onClose(); item.onClick() }, Modifier.fillMaxWidth())
    }
}
