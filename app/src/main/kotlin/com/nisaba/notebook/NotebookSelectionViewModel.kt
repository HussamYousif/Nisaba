package com.nisaba.notebook

import com.nisaba.domain.conversation.askAbout
import com.nisaba.domain.conversation.check
import com.nisaba.domain.conversation.redraw
import com.nisaba.domain.ink.InkColor
import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.ink.StrokeOrigin
import com.nisaba.domain.ink.loopBounds
import com.nisaba.domain.ink.movedBy
import com.nisaba.domain.ink.strokesInLoop
import com.nisaba.domain.ink.utteranceBounds
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.contains
import com.nisaba.domain.shared.inflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.hypot

// The lasso: a loop picks ink (or printed text); then move it, recolour it, delete it, check it, or ask about it.

private const val GRAB_PAD = 24f
private const val MIN_MOVE = 12f
private const val MIN_LOOP = 40f
internal const val NOTHING_TO_CHECK = "Nothing of yours to check there: write the working, then check it."

/** A lasso stroke (page units): a loop picks what is inside; a drag that starts inside the picked box moves it. */
internal fun NotebookViewModel.onLasso(points: InkPoints) {
    if (points.size < 2) return
    val sel = mutableState.value.selection
    val start = PagePoint(points.xs.first(), points.ys.first())
    val end = PagePoint(points.xs.last(), points.ys.last())
    if (sel != null && sel.ids.isNotEmpty() && sel.bounds.inflate(GRAB_PAD).contains(start)) {
        if (hypot(end.x - start.x, end.y - start.y) >= MIN_MOVE) moveSelection(end.x - start.x, end.y - start.y)
        return
    }
    val loop = List(points.size) { PagePoint(points.xs[it], points.ys[it]) }
    val picked = strokesInLoop(edit?.ink?.strokes.orEmpty(), loop)
    val bounds = utteranceBounds(picked) ?: loopBounds(loop).takeIf { it.width >= MIN_LOOP && it.height >= MIN_LOOP }
    mutableState.update { it.copy(selection = bounds?.let { b -> InkSelection(picked.mapTo(HashSet()) { s -> s.id }, b) }) }
}

fun NotebookViewModel.clearSelection() = mutableState.update { it.copy(selection = null) }

fun NotebookViewModel.toggleSelectionColours() =
    mutableState.update { s -> s.copy(selection = s.selection?.let { it.copy(colours = !it.colours) }) }

private fun NotebookViewModel.selected(): List<Stroke> {
    val ids = mutableState.value.selection?.ids ?: return emptyList()
    return edit?.ink?.strokes.orEmpty().filter { it.id in ids }
}

/** Moves the picked ink by [dx]/[dy] page units, as one undoable step; the box follows. */
internal fun NotebookViewModel.moveSelection(dx: Float, dy: Float) {
    val page = currentPage()?.id ?: return
    val moving = selected().ifEmpty { return }
    val moved = moving.map { it.movedBy(dx, dy) }
    ownScope.launch {
        if (!editOpenPage(page) { deps.ink.replace(it, moving, moved) }) return@launch
        mutableState.update { s ->
            val box = s.selection?.bounds?.let { b -> PageRect(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy) }
            s.copy(selection = box?.let { s.selection.copy(bounds = it) })
        }
        effectSink.emit(NotebookEffect.Refresh(full = false))
    }
}

fun NotebookViewModel.recolourSelection(argb: Int) {
    val page = currentPage()?.id ?: return
    val old = selected().ifEmpty { return }
    ownScope.launch {
        if (editOpenPage(page) { deps.ink.replace(it, old, old.map { s -> s.copy(color = InkColor(argb)) }) }) {
            mutableState.update { s -> s.copy(selection = s.selection?.copy(colours = false)) }
            effectSink.emit(NotebookEffect.Refresh(full = false))
        }
    }
}

/** Deletes the picked ink (one undoable step). An answer taken away whole leaves the conversation, as if erased. */
fun NotebookViewModel.deleteSelection() {
    val page = currentPage()?.id ?: return
    val gone = selected().ifEmpty { return }
    ownScope.launch {
        if (!editOpenPage(page) { deps.ink.remove(it, gone) }) return@launch
        clearSelection()
        effectSink.emit(NotebookEffect.Refresh(full = false))
        val left = edit?.ink?.strokes.orEmpty()
        gone.mapNotNull { (it.origin as? StrokeOrigin.Oracle)?.exchangeId }.distinct()
            .filter { ex -> left.none { (it.origin as? StrokeOrigin.Oracle)?.exchangeId == ex } }
            .forEach { deps.changes.markErased(it) }
    }
}

/** Asks about what the loop went around: their ink, an answer, or printed text. */
fun NotebookViewModel.askAboutSelection() {
    val sel = mutableState.value.selection ?: return
    val page = currentPage()?.id ?: return
    session?.askAbout(page, sel.ids, sel.bounds)
    clearSelection()
}

/** A ✓ or ✗ beside each line of the picked working. */
fun NotebookViewModel.checkSelection() {
    val page = currentPage()?.id ?: return
    if (session?.check(page, selected()) != true) mutableState.update { it.copy(notice = NOTHING_TO_CHECK) }
    clearSelection()
}

/** The picked sketch, redrawn cleanly below it. */
fun NotebookViewModel.redrawSelection() {
    val page = currentPage()?.id ?: return
    val sketch = selected().filter { it.origin is StrokeOrigin.User }.ifEmpty { return }
    session?.redraw(page, sketch)
    clearSelection()
}

/** ⋯ → Check my work: every line of their handwriting on this page. */
fun NotebookViewModel.checkWork() {
    val page = currentPage()?.id ?: return
    if (session?.check(page, edit?.ink?.strokes.orEmpty()) != true) mutableState.update { it.copy(notice = NOTHING_TO_CHECK) }
}
