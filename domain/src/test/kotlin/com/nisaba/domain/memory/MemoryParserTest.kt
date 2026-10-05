package com.nisaba.domain.memory

import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryParserTest {
    @Test fun readsRememberAndForgetInOrder() {
        val reply = """<answer>
            |Splendid.
            |</answer><forget id="3"/>
            |<remember category="books">Loved Dune</remember>
            |<remember category="Screen"> Watches Severance </remember><heard>dune!</heard>
        """.trimMargin()
        assertEquals(
            listOf(
                MemoryOp.Forget(FactId(3)),
                MemoryOp.Remember(FactCategory.Books, "Loved Dune"),
                MemoryOp.Remember(FactCategory.Screen, "Watches Severance"),
            ),
            parseMemoryOps(reply),
        )
    }

    @Test fun unknownOrMissingCategoryFallsBackToOther() {
        assertEquals(
            listOf(MemoryOp.Remember(FactCategory.Other, "a"), MemoryOp.Remember(FactCategory.Other, "b")),
            parseMemoryOps("""<remember category="cats">a</remember><remember>b</remember>"""),
        )
    }

    @Test fun forgetAcceptsHashedAndUnquotedIds() {
        assertEquals(
            listOf(MemoryOp.Forget(FactId(12)), MemoryOp.Forget(FactId(7))),
            parseMemoryOps("""<forget id="#12"/> <forget id=7 />"""),
        )
    }

    @Test fun noTagsNoOps() {
        assertEquals(emptyList(), parseMemoryOps("<answer>\nHi\n</answer><heard>hi</heard>"))
        assertEquals(emptyList(), parseMemoryOps("<remember category=\"me\">never closed"))
    }

    @Test fun stripLeavesTheRestOfTheLine() {
        assertEquals("Noted. ", stripMemoryTags("""Noted. <remember category="me">Is a nurse</remember>"""))
        assertEquals("", stripMemoryTags("""<forget id="2"/>"""))
    }
}
