package com.nisaba.notebook

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.nisaba.domain.notebook.Page
import com.nisaba.domain.notebook.pageLabel
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaDialog
import com.nisaba.ui.theme.NisabaTextField

private const val COLUMNS = 4
private const val ROWS = 3
internal const val PAGES_PER_SCREEN = COLUMNS * ROWS
private const val PAGE_ASPECT = 0.75f

/**
 * Every page as a small picture, a screenful at a time (no scrolling on e-ink). A tap opens it; a long press picks it
 * for Delete, Move earlier/later or Duplicate. A PDF notebook can jump to a printed page number.
 */
@Composable
fun PageGridComponent(state: NotebookUiState, vm: NotebookViewModel) {
    val screen = state.pageGridScreen
    val first = screen * PAGES_PER_SCREEN
    NisabaDialog("Pages", vm::closePageGrid, maxWidth = GRID_WIDTH_DP.dp) { // wide: thumbnails you can read
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (start in first until minOf(first + PAGES_PER_SCREEN, state.pages.size) step COLUMNS) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (i in start until minOf(start + COLUMNS, state.pages.size)) PageCell(state, i, vm, Modifier.weight(1f))
                    repeat(COLUMNS - (minOf(start + COLUMNS, state.pages.size) - start)) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        state.gridSelected?.let { PageActions(state, it, vm) }
        val screens = (state.pages.size + PAGES_PER_SCREEN - 1) / PAGES_PER_SCREEN
        if (screens > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                NisabaButton("‹ Earlier", { vm.pageGridScreen(screen - 1) }, enabled = screen > 0)
                Text("${screen + 1} of $screens", style = MaterialTheme.typography.labelLarge)
                NisabaButton("Later ›", { vm.pageGridScreen(screen + 1) }, enabled = screen < screens - 1)
            }
        }
        if (state.hasDocument) GoToPrintedPage(vm)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageCell(state: NotebookUiState, i: Int, vm: NotebookViewModel, modifier: Modifier) {
    val c = Nisaba.colors
    val page = state.pages[i]
    val picked = i == state.gridSelected
    var thumb by remember(page.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(page.id, state.strokes.size.takeIf { i == state.pageIndex }) { thumb = vm.thumbnail(page) }
    Column(modifier.combinedClickable(onClick = { vm.goToPage(i) }, onLongClick = { vm.selectGridPage(i) })) {
        val frame = if (i == state.pageIndex || picked) BorderStroke(3.dp, c.ink) else BorderStroke(1.dp, c.faint)
        Box(Modifier.fillMaxWidth().aspectRatio(PAGE_ASPECT).background(c.paper).border(frame)) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
        }
        Text(pageLabel(state.pages, i).substringBefore(" /"), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun PageActions(state: NotebookUiState, i: Int, vm: NotebookViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(pageLabel(state.pages, i).substringBefore(" /") + ":", style = MaterialTheme.typography.labelLarge)
        NisabaButton("Delete", vm::deleteGridPage, enabled = state.pages.size > 1)
        NisabaButton("‹ Move", { vm.moveGridPage(-1) }, enabled = i > 0)
        NisabaButton("Move ›", { vm.moveGridPage(1) }, enabled = i < state.pages.size - 1)
        NisabaButton("Duplicate", vm::duplicateGridPage)
    }
}

@Composable
private fun GoToPrintedPage(vm: NotebookViewModel) {
    var n by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Go to p.", style = MaterialTheme.typography.labelLarge)
        val digits = { v: String -> n = v.filter(Char::isDigit).take(MAX_DIGITS) }
        NisabaTextField(
            n, digits, Modifier.width(120.dp), onDone = { vm.goToPrintedPage(n) }, autoFocus = false,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        )
        NisabaButton("Go", { vm.goToPrintedPage(n) }, enabled = n.isNotEmpty())
    }
}

private const val MAX_DIGITS = 5

/** A page of this notebook as a small picture (null while it renders, or where there is none). */
internal suspend fun NotebookViewModel.thumbnail(page: Page) = deps.sources.thumbnail(page, notebook?.notebook?.paper)

private const val GRID_WIDTH_DP = 880
