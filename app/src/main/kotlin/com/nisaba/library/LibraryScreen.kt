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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.nisaba.domain.persona.PersonaRegistry
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
                NotebookGrid(state.notebooks, actions)
            }
        }
        state.importing?.let { p ->
            NisabaDialog("Pouring the ink…", {}) {
                Text("Reading the pages of your PDF: ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
            }
        }
        LibraryDialogs(state.dialog, actions)
        NewNotebookComponent(state.dialog, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookGrid(notebooks: List<CoverItem>, actions: LibraryActions) {
    val c = Nisaba.colors
    LazyVerticalGrid(
        GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        items(notebooks, key = { it.id.value }) { item ->
            Column(Modifier.combinedClickable(onClick = { actions.onOpen(item.id) }, onLongClick = { actions.onMenu(item) })) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(c.paper)) {
                    Canvas(Modifier.fillMaxSize()) { drawCover(item.seed, c.ink, c.lapis) }
                    HandTextComponent(
                        item.name, c.ink,
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).fillMaxWidth(coverNameWidth(item.name)),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        item.endless -> "Endless canvas"
                        item.lesson -> "Lesson · PDF"
                        else -> "${item.pageCount} page${if (item.pageCount == 1) "" else "s"}" + if (item.hasDocument) " · PDF" else ""
                    } + " · " + PersonaRegistry.of(item.personaId).name,
                    style = MaterialTheme.typography.labelSmall, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LibraryDialogs(dialog: LibraryDialog, actions: LibraryActions) {
    val onDismiss = actions.onDismiss
    when (val d = dialog) {
        LibraryDialog.None -> Unit
        is LibraryDialog.Menu -> NisabaDialog(d.name, onDismiss) {
            NisabaButton("Rename", { actions.onRenameRequested(d.id, d.name) }, Modifier.fillMaxWidth())
            NisabaButton("Delete", { actions.onDeleteRequested(d.id, d.name) }, Modifier.fillMaxWidth())
        }
        is LibraryDialog.Rename -> {
            var name by remember { mutableStateOf(d.name) }
            NisabaDialog("Rename", onDismiss) {
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
