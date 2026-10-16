package com.nisaba.notebook

import android.graphics.Rect
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nisaba.canvas.PageView
import com.nisaba.device.PenTool
import com.nisaba.ui.theme.NisabaIcon

// What sits over the page's edges: the bar and its chips, the star's badge, and where the pen must not write.

/**
 * The pen must not write under the bar, nor under the star handle that replaces it, nor under the lasso's bar, nor under
 * the chips at the top ([chipsPx] tall with the bar hidden): a tap on "Add" or a notice would also ink a dot.
 */
@Composable
internal fun PenExclusions(toolbarVisible: Boolean, pageView: PageView?, toolbarPx: Int, selecting: Boolean, chipsPx: Int) {
    val density = LocalDensity.current
    val handlePx = with(density) { (HANDLE_SIZE + 2 * HANDLE_MARGIN).dp.roundToPx() }
    val barPx = with(density) { SELECTION_BAR_HEIGHT.dp.roundToPx() }
    LaunchedEffect(toolbarVisible, pageView, selecting, toolbarPx, chipsPx) {
        val v = pageView ?: return@LaunchedEffect
        val handle = Rect(v.width - handlePx, v.height - handlePx, v.width, v.height)
        val chips = if (chipsPx > 0) listOf(Rect(0, 0, 10_000, chipsPx)) else emptyList()
        val top = if (toolbarVisible) listOf(Rect(0, 0, 10_000, toolbarPx)) else listOf(handle) + chips
        v.setExclusions(top + if (selecting) listOf(Rect(0, v.height - barPx, v.width, v.height)) else emptyList())
    }
}

/** The corner star's badge: a failure first, then work in progress, then a tool other than the pen, then muted. */
internal fun starBadge(state: NotebookUiState): StarBadge? = when {
    state.failure != null -> StarBadge.Failed
    state.working -> StarBadge.Working
    state.tool != PenTool.Pen -> StarBadge.Tool(
        when (state.tool) {
            PenTool.Shape -> NisabaIcon.Shape
            PenTool.AreaEraser -> NisabaIcon.AreaEraser
            PenTool.StrokeEraser -> NisabaIcon.Eraser
            else -> NisabaIcon.Lasso
        },
    )
    !state.listening -> StarBadge.Muted
    else -> null
}

/** The bar (when shown) and the one-line chips under it: a failure, a memory note, a notice, a reminder. */
@Composable
internal fun TopOfPage(
    state: NotebookUiState,
    vm: NotebookViewModel,
    onBack: () -> Unit,
    modifier: Modifier,
    onOpenSettings: () -> Unit = {},
) {
    Column(modifier) {
        if (state.toolbarVisible) NotebookToolbarComponent(state, vm, onBack)
        state.failure?.let { FailureChip(it, if (state.failureRetries) vm::retry else onOpenSettings, state.failureRetries) }
        state.book?.reading?.let { MemoryChip(readingLine(it), {}, mark = "…") }
        state.bookFailure?.let { FailureChip("Couldn't read the book: $it", vm::retryBook) }
        state.memoryNote?.let { MemoryChip(it, vm::dismissMemoryNote) }
        state.notice?.let { MemoryChip(it, vm::dismissNotice, mark = "!") }
        state.reminder?.let { ReminderChip(reminderLine(it), vm::addReminder, vm::dismissReminder) }
    }
}
