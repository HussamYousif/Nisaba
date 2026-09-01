package com.nisaba.notebook

import com.nisaba.notebook.model.Notebook
import com.nisaba.notebook.model.Stroke
import com.nisaba.notebook.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * NotebookSession (docs/architecture-review.md D-A): the single mutation path
 * — strokes, eraser, pages, undo/redo, content gate and save scheduling.
 * JVM-testable thanks to the injected SaveScheduler.
 */
class NotebookSessionTest {

    private class FakeScheduler : SaveScheduler {
        val scheduled = mutableListOf<Pair<Runnable, Long>>()
        val cancelled = mutableListOf<Runnable>()
        override fun schedule(task: Runnable, delayMs: Long) {
            scheduled.add(task to delayMs)
        }
        override fun cancel(task: Runnable) {
            cancelled.add(task)
        }
    }

    private lateinit var scheduler: FakeScheduler
    private lateinit var notebook: Notebook
    private lateinit var session: NotebookSession
    private val saves = mutableListOf<Notebook>()
    private val reasons = mutableListOf<NotebookSession.Reason>()

    @Before
    fun setUp() {
        scheduler = FakeScheduler()
        notebook = Notebook(id = "n1", title = "Test")
        session = NotebookSession(notebook, scheduler) { nb, _ -> saves.add(nb) }
        session.listener = object : NotebookSession.Listener {
            override fun onChanged(reason: NotebookSession.Reason) {
                reasons.add(reason)
            }
        }
    }

    private fun stroke(id: String, x: Float = 100f, y: Float = 100f) = Stroke(
        id = id,
        points = listOf(StrokePoint(x, y, 0.5f, 0L), StrokePoint(x + 50f, y + 50f, 0.5f, 100L)),
    )

    @Test
    fun addStroke_firesEvent_andUndoRedo() {
        session.addStroke(stroke("s1"))
        assertEquals(1, notebook.pages[0].strokes.size)
        assertEquals(1, session.contentVersion)
        assertEquals(listOf(NotebookSession.Reason.STROKES), reasons)

        session.undo()
        assertEquals(0, notebook.pages[0].strokes.size)
        session.redo()
        assertEquals(1, notebook.pages[0].strokes.size)
    }

    @Test
    fun eraseStroke_removesOnlyIntersecting() {
        session.addStroke(stroke("far", x = 1000f, y = 1000f))
        session.addStroke(stroke("near", x = 200f, y = 200f))
        reasons.clear()

        val removed = session.eraseStroke(listOf(StrokePoint(215f, 215f, 0.5f, 0L)))
        assertEquals(listOf("near"), removed.map { it.id })
        assertEquals(listOf("far"), notebook.pages[0].strokes.map { it.id })

        session.undo()
        assertEquals(2, notebook.pages[0].strokes.size)
    }

    @Test
    fun undoWithDuplicateIds_removesOnlyTheLastStroke() {
        // Audit P0-1 regression: id-filtering undo dropped every stroke that
        // shared an id ("s-preview" for all of them); the wholesale restore
        // must keep the other strokes intact.
        session.addStroke(stroke("dup"))
        session.addStroke(stroke("dup"))
        session.undo()
        assertEquals("one undo removes exactly one stroke", 1, notebook.pages[0].strokes.size)
        session.redo()
        assertEquals(2, notebook.pages[0].strokes.size)
    }

    @Test
    fun eraseUndo_restoresTheFullList() {
        session.addStroke(stroke("a", x = 1000f, y = 1000f))
        session.addStroke(stroke("b", x = 200f, y = 200f))
        session.eraseStroke(listOf(StrokePoint(215f, 215f, 0.5f, 0L)))
        assertEquals(listOf("a"), notebook.pages[0].strokes.map { it.id })

        session.undo()
        assertEquals(listOf("a", "b"), notebook.pages[0].strokes.map { it.id })
        session.redo()
        assertEquals(listOf("a"), notebook.pages[0].strokes.map { it.id })
    }

    @Test
    fun addPage_andUndoStructure() {
        session.addPage()
        assertEquals(2, notebook.pages.size)
        assertEquals(1, session.pageIndex)
        assertEquals(NotebookSession.Reason.STRUCTURE, reasons.last())

        session.undo()
        assertEquals(1, notebook.pages.size)
        assertEquals(0, session.pageIndex)
    }

    @Test
    fun turnPage_updatesIndex_andClearsUndo() {
        session.addPage()
        session.turnPage(-1)
        assertEquals(0, session.pageIndex)
        session.turnPage(-1) // clamped at first page
        assertEquals(0, session.pageIndex)
        session.turnPage(1)
        assertEquals(1, session.pageIndex)
        assertFalse(session.canUndo()) // undo stacks reset on page turn
    }

    @Test
    fun summonGate_comparesContentVersions() {
        assertEquals(-1, session.summonedVersion)
        session.addStroke(stroke("s1"))
        session.markSummoned()
        assertEquals(1, session.summonedVersion)
        assertFalse(session.contentVersion > session.summonedVersion) // nothing new

        session.addStroke(stroke("s2"))
        assertTrue(session.contentVersion > session.summonedVersion) // new content → gate open
    }

    @Test
    fun mutations_scheduleDebouncedSave_andSaveNowFlushes() {
        session.addStroke(stroke("s1"))
        assertEquals(1, scheduler.scheduled.size)
        assertEquals(800L, scheduler.scheduled[0].second)

        session.saveNow()
        assertTrue(scheduler.cancelled.isNotEmpty())
        assertEquals(1, saves.size)
        assertEquals("Test", saves[0].title)
        assertEquals(0, saves[0].lastOpenedPage)
    }

    @Test
    fun undoStack_isCappedAtFifty() {
        repeat(60) { session.addStroke(stroke("s$it")) }
        repeat(60) { session.undo() }
        // Only the last 50 strokes are undoable.
        assertEquals(10, notebook.pages[0].strokes.size)
    }
}
