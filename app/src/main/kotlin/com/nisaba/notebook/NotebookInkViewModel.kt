package com.nisaba.notebook

import com.nisaba.device.PenEvent
import com.nisaba.device.PenTool
import com.nisaba.domain.ink.EraseMode
import com.nisaba.domain.ink.InkColor
import com.nisaba.domain.ink.InkEditState
import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.ink.ShapeKind
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.ink.StrokeOrigin
import com.nisaba.domain.ink.recognizeShape
import com.nisaba.domain.ink.shapeStroke
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.StrokeId
import com.nisaba.domain.shared.toPage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// The pen on the page: strokes, shapes, erasing, undo/redo, all through the ink edit session.

fun NotebookViewModel.onPenEvent(e: PenEvent) {
    when (e) {
        is PenEvent.PenDown -> { session?.onPenDown(); hideToolbarLater() }
        is PenEvent.StrokeDone -> {
            val page = currentPage()?.id ?: return
            val points = toPagePoints(e.points) // mapped now: the view may pan or turn before the commit runs
            val tool = mutableState.value.tool
            when {
                tool.erases -> onPenEvent(PenEvent.EraseDone(List(e.points.size) { PagePoint(e.points.xs[it], e.points.ys[it]) }))
                tool == PenTool.Lasso -> {
                    onLasso(points); session?.onPenAborted()
                    effectSink.tryEmit(NotebookEffect.Repaint) // a pen that draws its own live ink drew the loop
                }
                tool == PenTool.Shape -> ownScope.launch { commitShape(page, points) }
                else -> ownScope.launch { commitStroke(page, points) }
            }
        }
        is PenEvent.EraseDone -> {
            val page = currentPage()?.id ?: return
            val path = e.path.map { affine.toPage(it.x, it.y) }
            val mode = if (mutableState.value.tool == PenTool.AreaEraser) EraseMode.Area else EraseMode.Stroke
            ownScope.launch { erase(page, mode, path); session?.onErased(edit?.ink?.strokes.orEmpty()); session?.onPenAborted() }
        }
    }
}

fun NotebookViewModel.undo() = ownScope.launch {
    usedToolbar()
    val page = currentPage()?.id ?: return@launch
    if (editOpenPage(page) { if (it.canUndo) deps.ink.undo(it) else null }) effectSink.emit(NotebookEffect.Refresh(full = false))
}

fun NotebookViewModel.redo() = ownScope.launch {
    usedToolbar()
    val page = currentPage()?.id ?: return@launch
    if (editOpenPage(page) { if (it.canRedo) deps.ink.redo(it) else null }) effectSink.emit(NotebookEffect.Refresh(full = false))
}

/**
 * Applies [change] to [pageId]'s ink, serialised with every other ink change. When that page is no longer open
 * (the user turned while the change was in flight) it is applied to the page's file instead, so nothing is lost or
 * lands on the wrong page.
 */
internal suspend fun NotebookViewModel.editAnyPage(pageId: PageId, change: suspend (InkEditState) -> InkEditState) {
    if (inkEdit.editAny(pageId, change)) publish()
}

/** Applies [change] only if [pageId] is still the open page (undo, redo); null = nothing to do. True if it changed. */
internal suspend fun NotebookViewModel.editOpenPage(pageId: PageId, change: suspend (InkEditState) -> InkEditState?): Boolean =
    inkEdit.editOpen(pageId, change).also { if (it) publish() }

/** A shape-tool stroke: a drag becomes the picked shape; in Auto a rough shape is cleaned up, anything else kept. */
internal suspend fun NotebookViewModel.commitShape(pageId: PageId, points: InkPoints) {
    if (points.size == 0) return
    val kind = mutableState.value.shape
    val clean = if (kind == ShapeKind.Auto) {
        recognizeShape(points)
    } else {
        shapeStroke(kind, PagePoint(points.xs.first(), points.ys.first()), PagePoint(points.xs.last(), points.ys.last()))
    }
    commitStroke(pageId, clean ?: points)
    if (clean != null) effectSink.emit(NotebookEffect.Repaint) // the freehand live ink gives way to the clean shape
}

internal suspend fun NotebookViewModel.commitStroke(pageId: PageId, points: InkPoints) {
    val s = mutableState.value
    val stroke = Stroke(StrokeId(deps.idGen.next()), points, s.penSize.width, InkColor(s.penInk), StrokeOrigin.User)
    editAnyPage(pageId) { deps.ink.commit(it, stroke) }
    if (currentPage()?.id == pageId) session?.onPenUp(stroke.id, stroke.bounds, pageId)
    deps.changes.touch(deps.notebookId)
    hideToolbarLater()
}

internal suspend fun NotebookViewModel.erase(pageId: PageId, mode: EraseMode, path: List<PagePoint>) {
    if (editOpenPage(pageId) { deps.ink.erase(it, mode, path, eraserRadius) }) effectSink.emit(NotebookEffect.Refresh(full = false))
}

internal fun NotebookViewModel.toPagePoints(v: InkPoints): InkPoints {
    val xs = FloatArray(v.size); val ys = FloatArray(v.size)
    for (i in 0 until v.size) { val p = affine.toPage(v.xs[i], v.ys[i]); xs[i] = p.x; ys[i] = p.y }
    return InkPoints(xs, ys, v.ps, v.ts)
}
