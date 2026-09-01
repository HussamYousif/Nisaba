package com.nisaba.notebook

import com.nisaba.ink.Eraser
import com.nisaba.notebook.model.AnswerGroup
import com.nisaba.notebook.model.Notebook
import com.nisaba.notebook.model.NotebookPage
import com.nisaba.notebook.model.Stroke
import com.nisaba.notebook.model.StrokePoint
import com.nisaba.notebook.model.TranscriptEntry
import java.util.ArrayDeque

/**
 * The notebook feature's single mutation path (docs/architecture-review.md
 * D-A). Owns state (page index, undo/redo stacks, content version for the
 * summon gate) and persistence scheduling; the UI listens via [Listener] and
 * never mutates the model directly.
 *
 * Core layer: no android.view, no network; the only outside dependency is the
 * ink domain's public leaf (Eraser).
 */
class NotebookSession(
    val notebook: Notebook,
    private val scheduler: SaveScheduler,
    private val onSave: (Notebook, thumbnail: Boolean) -> Unit,
) {

    enum class Reason { STROKES, PAGE, STRUCTURE, ANSWERS }

    interface Listener {
        fun onChanged(reason: Reason)
    }

    var listener: Listener? = null

    var pageIndex: Int = notebook.lastOpenedPage
        .coerceIn(0, (notebook.pages.size - 1).coerceAtLeast(0))
        private set

    /** Bumped by every mutation; the summon gate compares against [summonedVersion]. */
    var contentVersion: Int = 0
        private set

    /** Content version at the moment the last summon started (F3.4). */
    var summonedVersion: Int = -1
        private set

    private class Mutation(
        val reason: Reason,
        val undo: () -> Unit,
        val redo: () -> Unit,
    )

    private val undoStack = ArrayDeque<Mutation>()
    private val redoStack = ArrayDeque<Mutation>()

    // The 800 ms debounce must stay cheap: no thumbnail work per pause (P1-3).
    private val autosave = Runnable { onSave(notebook, false) }

    // ------------------------------------------------------------ pages

    fun currentPage(): NotebookPage? = notebook.pages.getOrNull(pageIndex)

    fun turnPage(delta: Int) {
        val target = (pageIndex + delta).coerceIn(0, notebook.pages.lastIndex)
        if (target == pageIndex) return
        saveNow()
        pageIndex = target
        notebook.lastOpenedPage = target
        undoStack.clear()
        redoStack.clear()
        changed(Reason.PAGE)
    }

    fun addPage() {
        saveNow()
        val index = notebook.pages.size
        val newPage = NotebookPage(index = index)
        val oldIndex = pageIndex
        notebook.pages = notebook.pages + newPage
        push(
            reason = Reason.STRUCTURE,
            undo = {
                notebook.pages = notebook.pages.filterNot { it.index == index }
                pageIndex = oldIndex
                notebook.lastOpenedPage = oldIndex
            },
            redo = {
                notebook.pages = notebook.pages + newPage
                pageIndex = index
                notebook.lastOpenedPage = index
            },
        )
        pageIndex = index
        notebook.lastOpenedPage = index
        contentVersion++
        changed(Reason.STRUCTURE)
    }

    // ------------------------------------------------------------ strokes

    fun addStroke(stroke: Stroke) {
        val page = currentPage() ?: return
        val before = page.strokes
        page.strokes = before + stroke
        push(
            reason = Reason.STROKES,
            // Wholesale restore: id-filtering corrupted the list when ids were
            // ever shared (audit P0-1), and stays correct if duplicates ever
            // reappear.
            undo = { page.strokes = before },
            redo = { page.strokes = before + stroke },
        )
        contentVersion++
        changed(Reason.STROKES)
    }

    /** Erases strokes near the eraser path; returns the removed ones. */
    fun eraseStroke(path: List<StrokePoint>): List<Stroke> {
        val page = currentPage() ?: return emptyList()
        val before = page.strokes
        val removed = Eraser.erase(page, path)
        if (removed.isEmpty()) return emptyList()
        push(
            reason = Reason.STROKES,
            undo = { page.strokes = before },
            redo = { page.strokes = before - removed.toSet() },
        )
        contentVersion++
        changed(Reason.STROKES)
        return removed
    }

    // ------------------------------------------------------------ undo/redo

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo() {
        val m = undoStack.pollLast() ?: return
        redoStack.addLast(Mutation(m.reason, m.redo, m.undo))
        m.undo()
        contentVersion++
        changed(m.reason)
    }

    fun redo() {
        val m = redoStack.pollLast() ?: return
        undoStack.addLast(Mutation(m.reason, m.redo, m.undo)) // back to original orientation
        m.undo() // the original redo action
        contentVersion++
        changed(m.reason)
    }

    // ------------------------------------------------------------ answers

    fun appendAnswer(group: AnswerGroup) {
        val page = currentPage() ?: return
        page.answers = page.answers + group
        changed(Reason.ANSWERS)
    }

    /**
     * Removes an answer and its transcript contribution (F5.3, M5): the
     * exchange disappears from context too. Undoable.
     */
    fun removeAnswer(groupId: String) {
        val page = currentPage() ?: return
        if (page.answers.none { it.id == groupId }) return
        val beforeAnswers = page.answers
        val beforeTranscript = notebook.transcript
        page.answers = beforeAnswers.filterNot { it.id == groupId }
        notebook.transcript = beforeTranscript.filterNot { it.answerGroupId == groupId }
        push(
            reason = Reason.ANSWERS,
            undo = {
                page.answers = beforeAnswers
                notebook.transcript = beforeTranscript
            },
            redo = {
                page.answers = beforeAnswers.filterNot { it.id == groupId }
                notebook.transcript = beforeTranscript.filterNot { it.answerGroupId == groupId }
            },
        )
        contentVersion++
        changed(Reason.ANSWERS)
    }

    fun appendTranscript(entries: List<TranscriptEntry>) {
        notebook.transcript = notebook.transcript + entries
    }

    /** Records that a summon started; the idle gate compares versions (F3.4). */
    fun markSummoned() {
        summonedVersion = contentVersion
    }

    // ------------------------------------------------------------ save

    fun requestSave() {
        scheduler.cancel(autosave)
        scheduler.schedule(autosave, AUTOSAVE_MS)
    }

    fun saveNow() {
        scheduler.cancel(autosave)
        notebook.lastOpenedPage = pageIndex
        onSave(notebook, true) // explicit save → fresh thumb.png
    }

    /** Flushes state and detaches (screen close / app pause). */
    fun close() {
        saveNow()
        listener = null
    }

    // ------------------------------------------------------------ internal

    private fun push(reason: Reason, undo: () -> Unit, redo: () -> Unit) {
        undoStack.addLast(Mutation(reason, undo, redo))
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun changed(reason: Reason) {
        listener?.onChanged(reason)
        requestSave()
    }

    companion object {
        private const val AUTOSAVE_MS = 800L
        private const val MAX_UNDO = 50
    }
}
