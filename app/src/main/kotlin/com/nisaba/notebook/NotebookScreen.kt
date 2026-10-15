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
import androidx.compose.foundation.layout.size
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
import com.nisaba.canvas.answerPen
import com.nisaba.device.EinkDevice
import com.nisaba.device.PenDevice
import com.nisaba.device.PenEvent
import com.nisaba.device.PenTool
import com.nisaba.domain.ink.StrokeOrigin
import com.nisaba.domain.lesson.TopicStatus
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.persona.VoiceDialogComponent
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
        vm.effects.collect { fx ->
            when (fx) {
                is NotebookEffect.Refresh -> if (fx.full) eink.fullRefresh(v) else eink.cleanPass(v, null)
                is NotebookEffect.PanTo -> v.jumpTo(fx.origin)
                NotebookEffect.Repaint -> { pen.suspendRawInk(); v.forgetLiveInk(); v.reloadBackground(); pen.resumeRawInk() }
            }
        }
    }
    // a PDF page's picture is rendered in the background, then the page repaints itself
    LaunchedEffect(state.background, pageView) {
        val v = pageView ?: return@LaunchedEffect
        if (state.background is PageBackground.DocumentPage) { ensureRaster(state.background, state.pageIndex); v.reloadBackground() }
    }
    LaunchedEffect(pen) { pen.events.collect { vm.onPenEvent(it) } }
    PenWiring(state, pageView, pen)
    PenExclusions(state.toolbarVisible, pageView, toolbarPx)
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
                    v.onLongPress = vm::onLongPress
                    pageView = v
                }
            },
            update = { v -> v.setPage(state.pageSize, state.background, state.viewOrigin); v.setStrokes(state.strokes) },
        )
        if (state.showHint) {
            HandTextComponent("Write, then pause.", Nisaba.colors.faint, Modifier.align(Alignment.Center).fillMaxWidth(HINT_WIDTH))
        }
        Column(Modifier.align(Alignment.TopStart)) {
            if (state.toolbarVisible) NotebookToolbarComponent(state, vm, onBack)
            state.failure?.let { FailureChip(it, vm::retry) }
            state.memoryNote?.let { MemoryChip(it, vm::dismissMemoryNote) }
        }
        if (!state.toolbarVisible) ToolbarHandle(Modifier.align(Alignment.BottomEnd), vm::showToolbar)
        if (state.menuOpen) NotebookMenuComponent(state.title, notebookMenu(state, vm, onOpenSettings), vm::closeMenu)
        NotebookOverlays(state, vm)
    }
}

/** The pen's ink follows the picked colour and size; it is paused while something is drawn over the page. */
@Composable
private fun PenWiring(state: NotebookUiState, pageView: PageView?, pen: PenDevice) {
    LaunchedEffect(state.tool) { pen.setTool(state.tool) }
    LaunchedEffect(state.penInk, state.penSize, pageView) { pageView?.setPen(state.penInk, state.penSize.width) }
    val overlay = state.overlayOpen // the Onyx layer would ink over a picker regardless of Compose
    DisposableEffect(overlay) {
        if (overlay) pen.suspendRawInk()
        onDispose { if (overlay) pen.resumeRawInk() }
    }
}

/** What can sit over the page: the page grid, the pen and shape pickers, the overview, the answer menu, rename. */
@Composable
private fun NotebookOverlays(state: NotebookUiState, vm: NotebookViewModel) {
    if (state.pageGridOpen) PageGridComponent(state, vm)
    when (state.toolPanel) {
        ToolPanel.Pen -> PenPanelComponent(state, vm)
        ToolPanel.Shapes -> ShapePanelComponent(state, vm)
        null -> Unit
    }
    if (state.overview) CanvasOverviewComponent(state, vm.visibleArea(), vm::jumpTo, vm::goHome, vm::closeOverview)
    state.answerMenu?.let { m ->
        NisabaDialog("This answer", vm::dismissAnswerMenu) {
            NisabaButton("Erase answer", { vm.eraseAnswer(m.exchangeId) }, Modifier.fillMaxWidth())
            if (m.canAskAgain) NisabaButton("Ask again", { vm.askAgain(m.exchangeId) }, Modifier.fillMaxWidth())
            NisabaButton("Cancel", vm::dismissAnswerMenu, Modifier.fillMaxWidth())
        }
    }
    if (state.progressOpen) LessonProgressDialog(state, vm::closeProgress)
    if (state.voiceOpen) VoiceDialogComponent(state.personaId, vm::setVoice, vm::closeVoice)
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
    animator.penFor = { answerPen(it, state.answerInk, state.codeHighlight) }
    view.showResumeMark(state.resumeMark)
    DisposableEffect(animator) {
        animator.onActiveChanged = { active -> if (active) { pen.suspendRawInk(); eink.fast() } else { pen.resumeRawInk(); eink.normal() } }
        animator.onDrained = { written, ref -> view.markDrawn(vm.onScribeDrained(written, ref, animator.writingOn)) }
        animator.onPageBreak = { written, toPage ->
            view.markDrawn(vm.onScribePageBreak(written, toPage, animator.lastRef, animator.writingOn))
        }
        view.isScribing = { animator.isActive }
        view.onSkipTap = { animator.skipToEnd() }
        view.onMarkTap = { vm.continueScribe() }
        view.onStylusDownWhileScribing = { vm.onPenEvent(PenEvent.PenDown(0f, 0f)) }
        vm.onScreenShown()
        onDispose {
            vm.onScreenLeft(animator.stop(), animator.lastRef, animator.writingOn)
            animator.reset()
        }
    }
    LaunchedEffect(animator) {
        vm.scribeCommands.collect { cmd ->
            when (cmd) {
                is ScribeCommand.Play -> animator.enqueue(cmd.strokes, cmd.ref, cmd.pageId)
                ScribeCommand.Stop -> view.markDrawn(vm.onScribeStopped(animator.stop(), animator.lastRef, animator.writingOn))
                ScribeCommand.Resume -> animator.resume()
                is ScribeCommand.PageReady -> animator.pageReady(cmd.page, cmd.pageId)
                ScribeCommand.Reset -> {
                    vm.onScribeAbandoned(animator.stop(), animator.lastRef, animator.writingOn)
                    animator.reset()
                }
                is ScribeCommand.AnswerAt -> view.prepareAnswerAt(cmd.at)
            }
        }
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
private fun MemoryChip(text: String, onDismiss: () -> Unit) {
    val c = Nisaba.colors
    Row(
        Modifier.padding(12.dp).background(c.paper).border(BorderStroke(2.dp, c.lapis)).clickable(onClick = onDismiss).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("✎", style = MaterialTheme.typography.titleLarge, color = c.lapis)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The ⋯ menu for this page. */
private fun notebookMenu(state: NotebookUiState, vm: NotebookViewModel, onOpenSettings: () -> Unit): List<MenuItem> = buildList {
    if (!state.endless) add(MenuItem("+ Page after this one", vm::insertBlankPage))
    if (!state.endless && state.aiAvailable && state.strokes.any { it.origin is StrokeOrigin.User }) {
        add(MenuItem("Tidy this page (a clean copy on a new page)", vm::tidyPage))
    }
    if (state.endless) { add(MenuItem("Overview", vm::showOverview)); add(MenuItem("Home", vm::goHome)) }
    if (state.lesson) add(MenuItem("Lesson progress", vm::showProgress))
    if (state.aiAvailable) add(MenuItem("Voice: ${PersonaRegistry.of(state.personaId).name}", vm::openVoice))
    add(MenuItem("Rename", vm::beginRename))
    add(MenuItem("Settings", onOpenSettings))
}

/** What the tutor has recorded: learned topics, then the shaky ones it will come back to. */
@Composable
private fun LessonProgressDialog(state: NotebookUiState, onClose: () -> Unit) {
    NisabaDialog("Lesson progress", onClose) {
        val learned = state.lessonTopics.filter { it.status == TopicStatus.Learned }
        val shaky = state.lessonTopics.filter { it.status == TopicStatus.Shaky }
        val body = MaterialTheme.typography.bodyLarge
        if (state.lessonTopics.isEmpty()) Text("Nothing recorded yet. Answer a question or two.", style = body)
        if (learned.isNotEmpty()) Text("Learned: " + learned.joinToString(", ") { it.topic }, style = body)
        if (shaky.isNotEmpty()) Text("To go over again: " + shaky.joinToString(", ") { it.topic }, style = body)
        NisabaButtonRow { NisabaButton("Close", onClose, filled = true) }
    }
}

/** The small Nisaba star in the corner while the bar is hidden: tap it to bring the bar back. */
@Composable
private fun ToolbarHandle(modifier: Modifier, onTap: () -> Unit) {
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(com.nisaba.R.drawable.ic_mark), "Show the toolbar",
        modifier.padding(HANDLE_MARGIN.dp).size(HANDLE_SIZE.dp).clickable(onClick = onTap),
    )
}

const val HANDLE_SIZE = 44
const val HANDLE_MARGIN = 16

/** The pen must not write under the bar, nor under the star handle that replaces it. */
@Composable
private fun PenExclusions(toolbarVisible: Boolean, pageView: PageView?, toolbarPx: Int) {
    val handlePx = with(LocalDensity.current) { (HANDLE_SIZE + 2 * HANDLE_MARGIN).dp.roundToPx() }
    LaunchedEffect(toolbarVisible, pageView) {
        val v = pageView ?: return@LaunchedEffect
        val handle = Rect(v.width - handlePx, v.height - handlePx, v.width, v.height)
        v.setExclusions(if (toolbarVisible) listOf(Rect(0, 0, 10_000, toolbarPx)) else listOf(handle))
    }
}
