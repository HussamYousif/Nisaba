package com.nisaba.notebook

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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nisaba.canvas.PageView
import com.nisaba.canvas.jumpTo
import com.nisaba.canvas.prepareAnswerAt
import com.nisaba.canvas.showResumeMark
import com.nisaba.canvas.showSelection
import com.nisaba.canvas.ScribeAnimator
import com.nisaba.canvas.answerPen
import com.nisaba.device.EinkDevice
import com.nisaba.device.PenDevice
import com.nisaba.device.PenEvent
import com.nisaba.domain.ink.StrokeOrigin
import com.nisaba.domain.lesson.TopicStatus
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.persona.LocalVoices
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
    ensureRaster: suspend (PageBackground, Int) -> Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val toolbarPx = with(LocalDensity.current) { TOOLBAR_HEIGHT.dp.roundToPx() }
    var pageView by remember { mutableStateOf<PageView?>(null) }
    val animator = remember(pageView) { pageView?.let { ScribeAnimator(it) } }

    NotebookEffects(vm, pageView, pen, eink)
    // a PDF page's picture is rendered in the background, then the page repaints itself
    LaunchedEffect(state.background, pageView) {
        val v = pageView ?: return@LaunchedEffect
        // drawn without its picture (not yet rendered): drawn again once it is; already drawn with it: nothing to do
        if (state.background is PageBackground.DocumentPage && ensureRaster(state.background, state.pageIndex)) v.reloadBackground()
    }
    LaunchedEffect(pen) { pen.events.collect { vm.onPenEvent(it) } }
    AppVisibility(vm)
    PenWiring(state, pageView, pen)
    var topPx by remember { androidx.compose.runtime.mutableIntStateOf(0) } // the bar and the chips under it
    PenExclusions(state.toolbarVisible, pageView, maxOf(toolbarPx, topPx), state.selection != null, chipsPx = topPx)
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
                    v.onMultiTap = { n -> if (n == 2) vm.undo(announce = true) else vm.redo(announce = true) } // can be a palm
                    v.onTopTap = { vm.showToolbar() }
                    v.onPan = vm::onPan
                    v.onLongPress = vm::onLongPress
                    pageView = v
                }
            },
            update = { v ->
                v.setPage(state.pageSize, state.background, state.viewOrigin, state.pageId, state.paper); v.setStrokes(state.strokes)
                v.showSelection(state.selection?.bounds ?: state.highlight)
            },
        )
        if (state.showHint) {
            val hint = LocalVoices.current.of(state.personaId).hint // in the notebook's voice
            HandTextComponent(hint, Nisaba.colors.faint, Modifier.align(Alignment.Center).fillMaxWidth(HINT_WIDTH))
        }
        TopOfPage(state, vm, onBack, Modifier.align(Alignment.TopStart).onSizeChanged { topPx = it.height })
        state.selection?.let { SelectionBarComponent(it, state.aiAvailable, vm, Modifier.align(Alignment.BottomStart)) }
        androidx.activity.compose.BackHandler(enabled = state.selection != null) { vm.doneWithSelection() }
        val handle = !state.toolbarVisible && state.selection == null
        if (handle) ToolbarHandle(Modifier.align(Alignment.BottomEnd), starBadge(state), vm::showToolbar, vm::openMiniBar)
        if (handle && state.miniBar) MiniBarComponent(state, vm, Modifier.align(Alignment.BottomEnd))
        androidx.activity.compose.BackHandler(enabled = handle && state.miniBar) { vm.closeMiniBar() }
        androidx.activity.compose.BackHandler(enabled = state.overview) { vm.closeOverview() }
        val voice = LocalVoices.current.of(state.personaId)
        if (state.menuOpen) NotebookMenuComponent(state.title, notebookMenu(state, voice.name, vm, onOpenSettings), vm::closeMenu)
        NotebookOverlays(state, vm)
    }
}

/** Screen off or another app in front: the hand stops like at a pen-down (its ink is saved); back again, a pause resumes. */
@Composable
private fun AppVisibility(vm: NotebookViewModel) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.onAppHidden()
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) vm.onAppShown()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
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
    if (state.paperOpen) PaperDialogComponent(state.paper, vm::setPaper, vm::closePaper)
    when (state.toolPanel) {
        ToolPanel.Pen -> PenPanelComponent(state, vm)
        ToolPanel.Shapes -> ShapePanelComponent(state, vm)
        null -> Unit
    }
    if (state.overview) CanvasOverviewComponent(state, vm.visibleArea(), vm::jumpTo, vm::goHome, vm::closeOverview)
    state.answerMenu?.let { AnswerMenuComponent(it, vm) }
    if (state.progressOpen) LessonProgressDialog(state, vm::closeProgress)
    if (state.bookOpen) state.book?.let { BookMapComponent(it, vm) }
    if (state.voiceOpen) VoiceDialogComponent(state.personaId, state.style, vm::setVoice, vm::closeVoice)
    if (state.renaming) {
        var name by remember { mutableStateOf(state.title) }
        NisabaDialog("Rename notebook", vm::cancelRename, keepOnOutsideTap = true) {
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
    animator.penFor = { tone, ref -> answerPen(tone, state.answerInk, state.codeHighlight, ref?.ink) }
    view.showResumeMark(state.resumeMark)
    DisposableEffect(animator) {
        animator.onActiveChanged = { active -> if (active) { pen.suspendRawInk(); eink.fast() } else { pen.resumeRawInk(); eink.normal() } }
        animator.onDrained = { written, ref -> view.markDrawn(vm.onScribeDrained(written, ref, animator.writingOn)) }
        animator.onBlockWritten = { written, ref -> view.markDrawn(vm.onScribeBlockWritten(written, ref, animator.writingOn)) }
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


/** What the ViewModel asks of the screen: refreshes, jumps, the clipboard, the share sheet, repaints. */
@Composable
private fun NotebookEffects(vm: NotebookViewModel, pageView: PageView?, pen: PenDevice, eink: EinkDevice) {
    val remind = rememberReminderHandler(vm)
    val exportAs = rememberExportHandler(vm)
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    LaunchedEffect(vm, pageView) {
        val v = pageView ?: return@LaunchedEffect
        vm.effects.collect { fx ->
            when (fx) {
                is NotebookEffect.Refresh -> if (fx.full) eink.fullRefresh(v) else eink.cleanPass(v, null)
                is NotebookEffect.PanTo -> v.jumpTo(fx.origin)
                is NotebookEffect.Copy -> clipboard.setText(androidx.compose.ui.text.AnnotatedString(fx.text))
                is NotebookEffect.Share -> context.startActivity(shareIntent(fx.subject, fx.text))
                is NotebookEffect.Remind -> remind(fx.reminder)
                is NotebookEffect.Export -> exportAs(fx)
                NotebookEffect.Repaint -> { pen.suspendRawInk(); v.forgetLiveInk(); v.reloadBackground(); pen.resumeRawInk() }
            }
        }
    }
}

private fun shareIntent(subject: String, text: String) = android.content.Intent.createChooser(
    android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_SUBJECT, subject).putExtra(android.content.Intent.EXTRA_TEXT, text),
    subject,
)
