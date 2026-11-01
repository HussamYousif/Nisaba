package com.nisaba.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import com.nisaba.persona.LocalVoices
import com.nisaba.persona.drawSigil
import com.nisaba.persona.inkOf
import com.nisaba.domain.shared.NotebookId
import com.nisaba.ui.brand.HandTextComponent
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaButtonRow
import com.nisaba.ui.theme.NisabaDialog
import com.nisaba.ui.theme.NisabaTextField

data class LibraryActions(
    val onOpen: (NotebookId) -> Unit,
    val onNew: () -> Unit,
    val onMenu: (CoverItem) -> Unit,
    val onRename: (NotebookId, String) -> Unit,
    val onDelete: (NotebookId) -> Unit,
    val onRenameRequested: (NotebookId, String) -> Unit,
    val onDeleteRequested: (NotebookId, String) -> Unit,
    val onDismiss: () -> Unit,
    val onSettings: () -> Unit = {},
    val onSearch: () -> Unit = {},
    // "New", step by step (NewNotebookComponent)
    val onChooseKind: (NotebookKind) -> Unit = {},
    val onSelectVoice: (String) -> Unit = {},
    val onSelectStyle: (com.nisaba.domain.notebook.AnswerStyle) -> Unit = {},
    val onConfirmVoice: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onCreate: (name: String) -> Unit = {},
    val onLearn: (goal: String) -> Unit = {},
    val animateWordmark: Boolean = false,
    val onWordmarkDone: () -> Unit = {},
    // library v2: screens of covers, pins, the trash
    val onGridPage: (Int) -> Unit = {},
    val onPin: (NotebookId, Boolean) -> Unit = { _, _ -> },
    val onTrash: () -> Unit = {},
    val onRestore: (NotebookId) -> Unit = {},
    val onDeleteForever: (NotebookId) -> Unit = {},
    val onEmptyTrash: () -> Unit = {},
)

/** Short names are written smaller so a two-letter notebook doesn't get gigantic letters. */
private fun coverNameWidth(name: String) = (0.09f * name.length + 0.22f).coerceIn(0.3f, 0.78f)

@Composable
fun LibraryScreen(state: LibraryUiState, actions: LibraryActions) {
    val c = Nisaba.colors
    Box(Modifier.fillMaxSize().background(c.paper)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    HandTextComponent(
                        "Nisaba", c.lapis, Modifier.width(230.dp), animate = actions.animateWordmark, onDone = actions.onWordmarkDone,
                    )
                }
                state.latest?.let { NisabaButton("Continue ›", { actions.onOpen(it.id) }); Spacer(Modifier.width(12.dp)) }
                if (state.trash.isNotEmpty()) {
                    NisabaButton("Trash (${state.trash.size})", actions.onTrash); Spacer(Modifier.width(12.dp))
                }
                NisabaButton("Search", actions.onSearch)
                Spacer(Modifier.width(12.dp))
                NisabaButton("Settings", actions.onSettings)
                Spacer(Modifier.width(12.dp))
                NisabaButton("New", actions.onNew, filled = true, enabled = state.importing == null)
            }
            Spacer(Modifier.height(28.dp))
            if (state.notebooks.isEmpty() && !state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Begin a notebook", style = MaterialTheme.typography.titleLarge, color = c.faint, textAlign = TextAlign.Center)
                }
            } else {
                NotebookGrid(state.visible, actions, Modifier.weight(1f))
                if (state.gridPages > 1) GridPager(state, actions)
            }
        }
        state.importing?.let { p ->
            NisabaDialog("Pouring the ink…", {}) {
                Text("Reading the pages of your PDF: ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
            }
        }
        LibraryDialogs(state.dialog, state.trash, actions)
        NewNotebookComponent(state.dialog, actions)
    }
}

/** One screenful of covers, three across and two down (no scrolling on e-ink). */
@Composable
private fun NotebookGrid(notebooks: List<CoverItem>, actions: LibraryActions, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        for (row in notebooks.chunked(COLUMNS)) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                for (item in row) CoverComponent(item, actions, Modifier.weight(1f))
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun GridPager(state: LibraryUiState, actions: LibraryActions) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        NisabaButton("‹ Earlier", { actions.onGridPage(-1) }, enabled = state.gridPage > 0)
        Text("${state.gridPage + 1} of ${state.gridPages}", style = MaterialTheme.typography.labelLarge)
        NisabaButton("Later ›", { actions.onGridPage(1) }, enabled = state.gridPage < state.gridPages - 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverComponent(item: CoverItem, actions: LibraryActions, modifier: Modifier) {
    val c = Nisaba.colors
    val voices = LocalVoices.current
    Column(modifier.combinedClickable(onClick = { actions.onOpen(item.id) }, onLongClick = { actions.onMenu(item) })) {
        Box(Modifier.fillMaxWidth().aspectRatio(COVER_ASPECT).background(c.paper)) {
            val voice = voices.of(item.personaId)
            Canvas(Modifier.fillMaxSize()) {
                drawCover(item.seed, c.ink, c.lapis)
                drawSigil(voice, inkOf(voice, c.lapis), Offset(size.width * SIGIL_X, size.width * SIGIL_Y), size.width * SIGIL_R)
            }
            HandTextComponent( // a long name (a PDF's file name) is cut, not written in lines that climb into the art
                coverName(item.name), c.ink,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).fillMaxWidth(coverNameWidth(coverName(item.name))),
            )
        }
        Spacer(Modifier.height(10.dp))
        val title = (if (item.pinned) "• " else "") + item.name // a pinned one is marked
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            when {
                item.endless -> "Endless canvas"
                item.lesson -> "Lesson · PDF"
                item.book -> "Book walk · " + item.progress.ifEmpty { "PDF" }
                else -> "${item.pageCount} page${if (item.pageCount == 1) "" else "s"}" + if (item.hasDocument) " · PDF" else ""
            } + " · " + voices.of(item.personaId).name + if (item.isPrivate) " · Private" else "",
            style = MaterialTheme.typography.labelSmall, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(item.edited, style = MaterialTheme.typography.labelSmall, color = c.faint, maxLines = 1)
    }
}

private const val COLUMNS = 3
private const val COVER_ASPECT = 0.8f

@Composable
private fun LibraryDialogs(dialog: LibraryDialog, trash: List<CoverItem>, actions: LibraryActions) {
    val onDismiss = actions.onDismiss
    when (val d = dialog) {
        LibraryDialog.None -> Unit
        is LibraryDialog.Menu -> NisabaDialog(d.name, onDismiss) {
            NisabaButton("Rename", { actions.onRenameRequested(d.id, d.name) }, Modifier.fillMaxWidth())
            NisabaButton(if (d.pinned) "Unpin" else "Pin to the front", { actions.onPin(d.id, !d.pinned) }, Modifier.fillMaxWidth())
            NisabaButton("Move to the trash", { actions.onDeleteRequested(d.id, d.name) }, Modifier.fillMaxWidth())
        }
        LibraryDialog.Trash -> TrashDialog(trash, actions)
        is LibraryDialog.Rename -> {
            var name by remember { mutableStateOf(d.name) }
            NisabaDialog("Rename notebook", onDismiss, keepOnOutsideTap = true) {
                NisabaTextField(name, { name = it }, onDone = { actions.onRename(d.id, name) })
                NisabaButtonRow { NisabaButton("Cancel", onDismiss); NisabaButton("Save", { actions.onRename(d.id, name) }, filled = true) }
            }
        }
        is LibraryDialog.Message -> NisabaDialog("Could not open it", onDismiss) {
            Text(d.text, style = MaterialTheme.typography.bodyLarge)
            NisabaButtonRow { NisabaButton("OK", onDismiss, filled = true) }
        }
        is LibraryDialog.ConfirmDelete -> NisabaDialog("Delete “${d.name}”?", onDismiss) {
            Text("Its pages and ink will be erased. This cannot be undone.", style = MaterialTheme.typography.bodyLarge)
            NisabaButtonRow { NisabaButton("Keep it", onDismiss); NisabaButton("Delete", { actions.onDelete(d.id) }, filled = true) }
        }
        else -> Unit // the steps of "New" (NewNotebookComponent)
    }
}

/** Deleted notebooks (kept 30 days): one row each, a screenful at most. */
@Composable
private fun TrashDialog(trash: List<CoverItem>, actions: LibraryActions) {
    NisabaDialog("Trash", actions.onDismiss) {
        if (trash.isEmpty()) Text("Nothing in the trash.", style = MaterialTheme.typography.bodyLarge)
        for (item in trash.take(TRASH_ROWS)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.edited, style = MaterialTheme.typography.labelSmall, color = Nisaba.colors.faint)
                }
                NisabaButton("Restore", { actions.onRestore(item.id) })
                com.nisaba.ui.theme.NisabaConfirmButton("Delete", "For good?", { actions.onDeleteForever(item.id) })
            }
        }
        Text("Kept 30 days, then deleted for good.", style = MaterialTheme.typography.labelSmall, color = Nisaba.colors.faint)
        NisabaButtonRow {
            com.nisaba.ui.theme.NisabaConfirmButton(
                "Empty the trash", "Delete all for good?", actions.onEmptyTrash, enabled = trash.isNotEmpty(),
            )
            NisabaButton("Close", actions.onDismiss, filled = true)
        }
    }
}

private const val TRASH_ROWS = 8

/** The voice's sign in the cover's top right corner (fractions of the cover's width). */
private const val SIGIL_X = 0.88f
private const val SIGIL_Y = 0.12f
private const val SIGIL_R = 0.065f // clear of the rosette's longest wedge

/** What the hand writes on a cover: the name, cut at a word near [COVER_NAME_CHARS] (about two lines) with "…". */
internal fun coverName(name: String): String {
    if (name.length <= COVER_NAME_CHARS) return name
    val cut = name.take(COVER_NAME_CHARS).let { head -> head.substringBeforeLast(' ').takeIf { it.length >= COVER_NAME_CHARS / 2 } ?: head }
    return cut.trimEnd(' ', ',', '-', '—', ':') + "…"
}

private const val COVER_NAME_CHARS = 30
