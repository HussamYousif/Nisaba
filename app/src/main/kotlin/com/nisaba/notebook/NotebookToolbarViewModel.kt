package com.nisaba.notebook

import com.nisaba.device.PenTool
import com.nisaba.domain.ink.ShapeKind
import com.nisaba.domain.settings.PenSize
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.PageSize
import com.nisaba.notebook.NotebookViewModel.Companion.PAGE_GRID_SIZE
import com.nisaba.notebook.NotebookViewModel.Companion.RESHOW_GUARD_MILLIS
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


// The bar and what it opens: menus, pickers, the page grid, the overview, rename, and when the bar hides.
fun NotebookViewModel.openPageGrid() {
    hideJob?.cancel()
    mutableState.update { it.copy(pageGridOpen = true, pageGridScreen = pageIndex / PAGE_GRID_SIZE) }
}
fun NotebookViewModel.closePageGrid() { mutableState.update { it.copy(pageGridOpen = false) }; hideToolbarLater(toolbarUseMillis) }
fun NotebookViewModel.pageGridScreen(n: Int) = mutableState.update { it.copy(pageGridScreen = n.coerceAtLeast(0)) }

/** Opens page [index] (0-based) from the grid. */
fun NotebookViewModel.goToPage(index: Int) {
    closePageGrid()
    if (index == pageIndex) return
    ownScope.launch { openPage(index, full = true) }
}

fun NotebookViewModel.openMenu() { hideJob?.cancel(); mutableState.update { it.copy(menuOpen = true) } }
fun NotebookViewModel.closeMenu() { mutableState.update { it.copy(menuOpen = false) }; hideToolbarLater(toolbarUseMillis) }

fun NotebookViewModel.openVoice() = mutableState.update { it.copy(voiceOpen = true) }
fun NotebookViewModel.closeVoice() = mutableState.update { it.copy(voiceOpen = false) }

/** From the next reply on, Nisaba answers in this notebook in the voice [personaId]. */
fun NotebookViewModel.setVoice(personaId: String, style: com.nisaba.domain.notebook.AnswerStyle) {
    closeVoice()
    val set = deps.changes.setPersona ?: return
    ownScope.launch { set(deps.notebookId, personaId); deps.changes.setStyle(deps.notebookId, style) }
}

fun NotebookViewModel.showProgress() = mutableState.update { it.copy(progressOpen = true) }
fun NotebookViewModel.closeProgress() = mutableState.update { it.copy(progressOpen = false) }

fun NotebookViewModel.showOverview() = mutableState.update { it.copy(overview = true) }
fun NotebookViewModel.closeOverview() = mutableState.update { it.copy(overview = false) }

/** What the view shows now (page space), for the overview's outline. */
fun NotebookViewModel.visibleArea(): PageRect {
    val o = viewOrigin ?: PagePoint(0f, 0f)
    return PageRect(o.x, o.y, o.x + viewW, o.y + viewH)
}

/** Centres the endless canvas on [p] (a spot picked in the overview). */
fun NotebookViewModel.jumpTo(p: PagePoint) = goTo(PagePoint(p.x - viewW / 2f, p.y - viewH / 2f))

/** Back to where the canvas starts. */
fun NotebookViewModel.goHome() = goTo(PagePoint(PageSize.ENDLESS_ORIGIN, PageSize.ENDLESS_ORIGIN))

internal fun NotebookViewModel.goTo(origin: PagePoint) {
    closeOverview()
    ownScope.launch { effectSink.emit(NotebookEffect.PanTo(origin)); effectSink.emit(NotebookEffect.Refresh(full = true)) }
}

fun NotebookViewModel.setTool(tool: PenTool) { mutableState.update { it.copy(tool = tool) }; usedToolbar() }
/** The pen button: picks the pen, or (when it already is) opens its colours and sizes. */
fun NotebookViewModel.tapPen() = if (mutableState.value.tool == PenTool.Pen) openPanel(ToolPanel.Pen) else setTool(PenTool.Pen)

/** The shape button: picks the shape tool, or (when it already is) opens the shapes. */
fun NotebookViewModel.tapShape() = if (mutableState.value.tool == PenTool.Shape) openPanel(ToolPanel.Shapes) else setTool(PenTool.Shape)

internal fun NotebookViewModel.openPanel(panel: ToolPanel) { hideJob?.cancel(); mutableState.update { it.copy(toolPanel = panel) } }
fun NotebookViewModel.closePanel() { mutableState.update { it.copy(toolPanel = null) }; hideToolbarLater(toolbarUseMillis) }

fun NotebookViewModel.setPenInk(argb: Int) = savePen(argb, mutableState.value.penSize)
fun NotebookViewModel.setPenSize(size: PenSize) = savePen(mutableState.value.penInk, size)

internal fun NotebookViewModel.savePen(ink: Int, size: PenSize) {
    mutableState.update { it.copy(penInk = ink, penSize = size) }
    ownScope.launch { deps.memory.savePen(ink, size) }
}

/** Picks [kind] and closes the panel; the shape tool stays selected. */
fun NotebookViewModel.setShape(kind: ShapeKind) { mutableState.update { it.copy(shape = kind, tool = PenTool.Shape) }; closePanel() }

/** A tap brings the bar back, unless it only just hid (a tap racing the hide would make it flash). */
fun NotebookViewModel.showToolbar() {
    if (!mutableState.value.toolbarVisible && nowMillis() - hiddenAt < RESHOW_GUARD_MILLIS) return
    mutableState.update { it.copy(toolbarVisible = true) }
    hideToolbarLater(toolbarUseMillis)
}

fun NotebookViewModel.beginRename() = mutableState.update { it.copy(renaming = true) }
fun NotebookViewModel.cancelRename() = mutableState.update { it.copy(renaming = false) }
fun NotebookViewModel.confirmRename(name: String) {
    ownScope.launch { deps.rename(deps.notebookId, name); mutableState.update { it.copy(renaming = false) } }
}

/** Hides the bar [millis] from now: soon after writing, a little later after the bar itself was used. */
internal fun NotebookViewModel.hideToolbarLater(millis: Long = toolbarHideMillis) {
    hideJob?.cancel()
    hideJob = ownScope.launch {
        delay(millis)
        val s = mutableState.value
        if (s.toolbarVisible && !s.pickerOpen) { // never pull the bar from under an open menu
            mutableState.update { it.copy(toolbarVisible = false) }
            hiddenAt = nowMillis()
            effectSink.emit(NotebookEffect.Refresh(full = false))
        }
    }
}

/** The bar was used: keep it up a little longer. */
internal fun NotebookViewModel.usedToolbar() { if (mutableState.value.toolbarVisible) hideToolbarLater(toolbarUseMillis) }
