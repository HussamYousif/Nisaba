package com.nisaba.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Structured answer parsing (docs/07 §6): contract validation, tolerance for
 * malformed blocks, and rejection of non-JSON output.
 */
class AnswerParserTest {

    @Test
    fun parsesAllBlockTypes() {
        val raw = """
            {"blocks":[
              {"type":"text","markdown":"**Hello** *world*"},
              {"type":"drawing","svg":"<svg viewBox='0 0 1000 1000'><path d='M10 10 L100 100'/></svg>"},
              {"type":"table","title":"T","headers":["A","B"],"rows":[["1","2"],["3","4"]]}
            ]}
        """.trimIndent()
        val blocks = AnswerParser.parse(raw)
        assertEquals(3, blocks.size)
        assertEquals("text", blocks[0].type)
        assertEquals("**Hello** *world*", blocks[0].markdown)
        assertEquals("drawing", blocks[1].type)
        assertTrue(blocks[1].svg!!.contains("<path"))
        assertEquals("table", blocks[2].type)
        assertEquals(listOf("A", "B"), blocks[2].headers)
        assertEquals(2, blocks[2].rows!!.size)
    }

    @Test
    fun unknownBlockTypes_areDropped() {
        val raw = """{"blocks":[{"type":"video","url":"x"},{"type":"text","markdown":"ok"}]}"""
        val blocks = AnswerParser.parse(raw)
        assertEquals(1, blocks.size)
        assertEquals("text", blocks[0].type)
    }

    @Test
    fun malformedDrawing_becomesSmudgePlaceholder() {
        val raw = """{"blocks":[{"type":"drawing","svg":""},{"type":"text","markdown":"still fine"}]}"""
        val blocks = AnswerParser.parse(raw)
        assertEquals(2, blocks.size)
        assertEquals("text", blocks[0].type) // smudge placeholder is a text block
        assertTrue(blocks[0].markdown!!.contains("smudged"))
    }

    @Test
    fun blockCount_isCapped() {
        val many = (0 until 20).joinToString(",") { """{"type":"text","markdown":"b$it"}""" }
        val blocks = AnswerParser.parse("""{"blocks":[$many]}""")
        assertEquals(8, blocks.size)
    }

    @Test
    fun oversizedCells_areTruncated() {
        val raw = """{"blocks":[{"type":"table","headers":["${"x".repeat(80)}"],"rows":[["${"y".repeat(60)}"]]}]}"""
        val blocks = AnswerParser.parse(raw)
        assertEquals(40, blocks[0].headers!![0].length)
        assertEquals(40, blocks[0].rows!![0][0].length)
    }

    @Test
    fun nonJson_throws() {
        try {
            AnswerParser.parse("I am a friendly scribe, here is your answer in prose…")
            fail("should have thrown")
        } catch (e: AnswerParser.ParseException) {
            assertTrue(e.message!!.contains("JSON"))
        }
    }

    @Test
    fun emptyBlocks_throws() {
        try {
            AnswerParser.parse("""{"blocks":[]}""")
            fail("should have thrown")
        } catch (_: AnswerParser.ParseException) {
        }
    }
}
