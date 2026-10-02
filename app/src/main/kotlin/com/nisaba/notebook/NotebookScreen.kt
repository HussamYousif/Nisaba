package com.nisaba.notebook

import android.graphics.Rect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nisaba.canvas.PageView
import com.nisaba.canvas.ScribeAnimator
import com.nisaba.device.EinkDevice
import com.nisaba.device.PenDevice
import com.nisaba.device.PenEvent
import com.nisaba.device.PenTool
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.infra.render.BackgroundRenderer
import com.nisaba.infra.render.InkRenderer
import com.nisaba.ui.brand.HandTextComponent
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaButtonRow
import com.nisaba.ui.theme.NisabaDialog
import com.nisaba.ui.theme.NisabaTextField

private const val HINT_WIDTH = 0.5f

@Composable
fun NotebookScreen(
    vm: NotebookViewModel,
    pen: PenDevice,
    eink: EinkDevice,
    ink: InkRenderer,
    background: BackgroundRenderer,
    ensureRaster: suspend (PageBackground, Int) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val toolbarPx = with(LocalDensity.current) { TOOLBAR_HEIGHT.dp.roundToPx() }
    var pageView by remember { mutableStateOf<PageView?>(null) }
    val animator = remember(pageView) { pageView?.let { ScribeAnimator(it) } }

    // panel refresh requests from the ViewModel
    LaunchedEffect(vm, pageView) {
        val v = pageView ?: return@LaunchedEffect
        vm.effects.collect { fx -> if (fx is NotebookEffect.Refresh) { if (fx.full) eink.fullRefresh(v) else eink.cleanPass(v, null) } }
    }
    // a PDF page's picture is rendered in the background, then the page repaints itself
    LaunchedEffect(state.background, pageView) {
        val v = pageView ?: return@LaunchedEffect
        if (state.background is PageBackground.DocumentPage) { ensureRaster(state.background, state.pageIndex); v.reloadBackground() }
    }
    LaunchedEffect(pen) { pen.events.collect { vm.onPenEvent(it) } }
    LaunchedEffect(state.tool) { pen.setTool(state.tool) }
    LaunchedEffect(state.toolbarVisible, pageView) {
        pageView?.setExclusions(if (state.toolbarVisible) listOf(Rect(0, 0, 10_000, toolbarPx)) else emptyList())
    }
    DisposableEffect(Unit) { eink.fast(); onDispose { eink.normal() } }

    ScribeWiring(vm, state, pageView, animator, pen, eink)

    Box(Modifier.fillMaxSize().background(Nisaba.colors.paper)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PageView(context, pen, ink, background).also { v ->
                    v.topStripPx = toolbarPx
                    v.onSizeKnown = { w, h -> vm.onViewSize(w, h) }
                    v.onSwipe = { vm.turnPage(it) }
                    v.onMultiTap = { n -> if (n == 2) vm.undo() else vm.redo() }
                    v.onTopTap = { vm.showToolbar() }
                    v.onPan = vm::onPan
                    pageView = v
                }
            },
            update = { v -> v.setPage(state.pageSize, state.background, state.viewOrigin); v.setStrokes(state.strokes) },
        )
        if (state.showHint) {
            HandTextComponent("Write, then pause.", Nisaba.colors.faint, Modifier.align(Alignment.Center).fillMaxWidth(HINT_WIDTH))
        }
        Column(Modifier.align(Alignment.TopStart)) {
            if (state.toolbarVisible) Toolbar(state, vm, onBack, onOpenSettings)
            state.failure?.let { FailureChip(it, vm::retry) }
        }
        if (state.renaming) {
            var name by remember { mutableStateOf(state.title) }
            NisabaDialog("Rename notebook", vm::cancelRename) {
                NisabaTextField(name, { name = it }, onDone = { vm.confirmRename(name) })
                NisabaButtonRow {
                    NisabaButton("Cancel", vm::cancelRename)
                    NisabaButton("Save", { vm.confirmRename(name) }, filled = true)
                }
            }
        }
    }
}

/** Connects the ViewModel's scribe commands to the page view and its animator, and the pen/panel to the hand's state. */
@Composable
private fun ScribeWiring(
    vm: NotebookViewModel,
    state: NotebookUiState,
    view: PageView?,
    animator: ScribeAnimator?,
    pen: PenDevice,
    eink: EinkDevice,
) {
    if (view == null || animator == null) return
    animator.color = state.answerColor
    view.showResumeMark(state.resumeMark)
    DisposableEffect(animator) {
        animator.onActiveChanged = { active -> if (active) { pen.suspendRawInk(); eink.fast() } else { pen.resumeRawInk(); eink.normal() } }
        animator.onDrained = { written -> view.markDrawn(vm.onScribeDrained(written)) }
        animator.onPageBreak = { written, toPage -> view.markDrawn(vm.onScribePageBreak(written, toPage)) }
        view.isScribing = { animator.isActive }
        view.onSkipTap = { animator.skipToEnd() }
        view.onMarkTap = { vm.continueScribe() }
        view.onStylusDownWhileScribing = { vm.onPenEvent(PenEvent.PenDown(0f, 0f)) }
        onDispose { animator.reset(); view.showInkwell(null) }
    }
    LaunchedEffect(animator) {
        vm.scribeCommands.collect { cmd ->
            when (cmd) {
                is ScribeCommand.Play -> animator.enqueue(cmd.strokes)
                ScribeCommand.Stop -> view.markDrawn(vm.onScribeStopped(animator.stop()))
                ScribeCommand.Resume -> animator.resume()
                is ScribeCommand.PageReady -> animator.pageReady(cmd.page)
                ScribeCommand.Reset -> { animator.reset(); view.showInkwell(null) }
                is ScribeCommand.Inkwell -> view.showInkwell(cmd.at)
            }
        }
    }
}

@Composable
private fun Toolbar(state: NotebookUiState, vm: NotebookViewModel, onBack: () -> Unit, onSettings: () -> Unit) {
    val c = Nisaba.colors
    Row(
        Modifier.fillMaxWidth().height(TOOLBAR_HEIGHT.dp).background(c.paper).border(BorderStroke(1.dp, c.ink)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip("‹ Back", onClick = onBack)
        Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.clickable { vm.beginRename() })
        Spacer(Modifier.weight(1f))
        if (!state.endless) Text("${state.pageIndex + 1} / ${state.pageCount}", style = MaterialTheme.typography.labelLarge)
        Chip("Pen", selected = state.tool == PenTool.Pen) { vm.setTool(PenTool.Pen) }
        Chip(if (state.tool == PenTool.AreaEraser) "Erase·area" else "Erase", selected = state.tool != PenTool.Pen) {
            vm.setTool(if (state.tool == PenTool.StrokeEraser) PenTool.AreaEraser else PenTool.StrokeEraser)
        }
        if (!state.endless) Chip("+ Page") { vm.insertBlankPage() }
        Chip("Undo", enabled = state.canUndo) { vm.undo() }
        Chip("Redo", enabled = state.canRedo) { vm.redo() }
        if (state.aiAvailable) {
            Chip(if (state.listening) "Listening" else "Muted", selected = state.listening) { vm.toggleListening() }
            Chip("Ask now") { vm.askNow() }
        }
        Chip("⚙", onClick = onSettings)
    }
}

@Composable
private fun FailureChip(text: String, onRetry: () -> Unit) {
    val c = Nisaba.colors
    Row(
        Modifier.padding(12.dp).background(c.paper).border(BorderStroke(2.dp, c.lapis)).clickable(onClick = onRetry).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("?", style = MaterialTheme.typography.titleLarge, color = c.lapis)
        Text("$text · tap to retry", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Chip(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) =
    NisabaButton(text, onClick, filled = selected, enabled = enabled)

private const val TOOLBAR_HEIGHT = 56
