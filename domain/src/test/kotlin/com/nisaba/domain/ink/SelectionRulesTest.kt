package com.nisaba.domain.ink

import com.nisaba.domain.notebook.FakeInkStore
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PagePoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectionRulesTest {
    private fun box(l: Float, t: Float, r: Float, b: Float) = listOf(PagePoint(l, t), PagePoint(r, t), PagePoint(r, b), PagePoint(l, b))

    @Test fun aLoopPicksWhatIsMostlyInsideIt() {
        val inside = lineStroke("in", 120f, 300f, 200f)
        val half = lineStroke("half", 250f, 600f, 210f) // mostly outside
        val far = lineStroke("far", 100f, 400f, 900f)
        assertEquals(listOf("in"), strokesInLoop(listOf(inside, half, far), box(100f, 150f, 400f, 260f)).map { it.id.value })
        // drawn the other way round: the same
        assertEquals(listOf("in"), strokesInLoop(listOf(inside, half, far), box(100f, 150f, 400f, 260f).reversed()).map { it.id.value })
        assertTrue(strokesInLoop(listOf(inside), listOf(PagePoint(0f, 0f), PagePoint(9f, 9f))).isEmpty())
    }

    @Test fun handwritingFallsIntoLinesTopToBottomWithDotsOnTheirLine() {
        val l1 = listOf(tallStroke("a", 100f, 100f, 40f), tallStroke("b", 160f, 104f, 36f))
        val dot = tallStroke("dot", 180f, 80f, 6f) // the dot of an i, above line 1
        val l2 = listOf(tallStroke("c", 100f, 220f, 40f), tallStroke("d", 300f, 225f, 30f))
        val l3 = listOf(tallStroke("e", 120f, 340f, 42f))
        val lines = inkLines((l3 + l2 + dot + l1).shuffled(kotlin.random.Random(3)))
        assertEquals(3, lines.size)
        assertEquals(listOf("a", "b", "dot"), lines[0].strokes.map { it.id.value })
        assertEquals(listOf("c", "d"), lines[1].strokes.map { it.id.value })
        assertTrue(lines[0].bounds.bottom < lines[1].bounds.top && lines[1].bounds.bottom < lines[2].bounds.top)
    }

    @Test fun movingKeepsTheStrokeAndIsOneUndoStep() = runTest {
        val a = lineStroke("a", 100f, 200f, 300f)
        val moved = a.movedBy(50f, -20f)
        assertEquals(a.id, moved.id)
        assertEquals(150f, moved.bounds.left); assertEquals(280f, moved.bounds.top)
        val store = FakeInkStore()
        val start = InkEditState(PageInk(PageId("p"), listOf(a)))
        val after = ReplaceStrokesUseCase(store)(start, listOf(a), listOf(moved))
        assertEquals(listOf(moved), after.ink.strokes)
        assertEquals(listOf(a), UndoUseCase(store)(after).ink.strokes)
        assertTrue(RemoveStrokesUseCase(store)(after, listOf(moved)).ink.strokes.isEmpty())
    }
}
