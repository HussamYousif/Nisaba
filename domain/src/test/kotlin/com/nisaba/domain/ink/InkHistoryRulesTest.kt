package com.nisaba.domain.ink

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InkHistoryRulesTest {
    private val a = lineStroke("a", 0f, 50f, 10f)
    private val b = lineStroke("b", 0f, 50f, 20f)
    private val c = lineStroke("c", 0f, 50f, 30f)

    @Test fun addRemoveReplaceApply() {
        var l = applyCommand(emptyList(), InkCommand.Add(listOf(a, b)))
        assertEquals(listOf(a, b), l)
        l = applyCommand(l, InkCommand.Add(listOf(a)))                       // duplicate ids ignored
        assertEquals(2, l.size)
        l = applyCommand(l, InkCommand.Replace(removed = listOf(a), added = listOf(c)))
        assertEquals(setOf("b", "c"), l.map { it.id.value }.toSet())
        assertEquals(listOf(b), applyCommand(l, InkCommand.Remove(listOf(c))))
    }

    @Test fun anAnswerCommittedBlockByBlockIsOneUndoStep() {
        fun answer(s: Stroke, ex: String) = s.copy(origin = StrokeOrigin.Oracle(com.nisaba.domain.shared.ExchangeId(ex)))
        var h = push(InkHistory(), InkCommand.Add(listOf(answer(a, "x"))))
        h = push(h, InkCommand.Add(listOf(answer(b, "x"))))                 // the next block of the same answer
        assertEquals(1, h.undo.size)
        assertEquals(InkCommand.Remove(listOf(answer(a, "x"), answer(b, "x"))), undo(h)!!.second)
        h = push(h, InkCommand.Add(listOf(answer(c, "y"))))                 // another answer: its own step
        assertEquals(2, h.undo.size)
        h = push(push(h, InkCommand.Add(listOf(a))), InkCommand.Add(listOf(b))) // the person's strokes never merge
        assertEquals(4, h.undo.size)
    }

    @Test fun undoRedoWalkTheStack() {
        var h = InkHistory()
        assertNull(undo(h)); assertNull(redo(h))
        h = push(push(h, InkCommand.Add(listOf(a))), InkCommand.Add(listOf(b)))
        val (h1, cmd) = undo(h)!!
        assertEquals(InkCommand.Remove(listOf(b)), cmd)
        assertEquals(1, h1.undo.size); assertEquals(1, h1.redo.size)
        val (h2, again) = redo(h1)!!
        assertEquals(InkCommand.Add(listOf(b)), again)
        assertEquals(2, h2.undo.size); assertEquals(0, h2.redo.size)
    }

    @Test fun newCommandClearsRedoAndHistoryIsCapped() {
        var h = push(InkHistory(), InkCommand.Add(listOf(a)))
        h = undo(h)!!.first
        assertEquals(1, h.redo.size)
        h = push(h, InkCommand.Add(listOf(b)))
        assertEquals(0, h.redo.size)
        repeat(MAX_UNDO + 50) { h = push(h, InkCommand.Add(listOf(c))) }
        assertEquals(MAX_UNDO, h.undo.size)
    }

    @Test fun invertingTwiceIsIdentity() {
        val cmds = listOf(InkCommand.Add(listOf(a)), InkCommand.Remove(listOf(b)), InkCommand.Replace(listOf(a), listOf(b, c)))
        cmds.forEach { assertEquals(it, invert(invert(it))) }
    }

    @Test fun randomCommandThenInverseRestoresTheSameStrokeSet() {
        val rnd = Random(7)
        val pool = (0 until 12).map { lineStroke("p$it", 0f, 50f, it * 10f) }
        repeat(200) {
            val base = pool.shuffled(rnd).take(rnd.nextInt(0, 8))
            val outside = pool - base.toSet()
            val cmd: InkCommand = when (rnd.nextInt(3)) {
                0 -> InkCommand.Add(outside.shuffled(rnd).take(rnd.nextInt(1, 4)))
                1 -> InkCommand.Remove(base.shuffled(rnd).take(rnd.nextInt(0, 3)))
                else -> {
                    val rem = base.shuffled(rnd).take(rnd.nextInt(0, 3))
                    InkCommand.Replace(rem, outside.shuffled(rnd).take(rnd.nextInt(0, 3)))
                }
            }
            val after = applyCommand(base, cmd)
            val back = applyCommand(after, invert(cmd))
            assertEquals(base.map { it.id }.toSet(), back.map { it.id }.toSet(), "cmd=$cmd")
        }
    }
}
