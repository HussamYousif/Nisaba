package com.nisaba.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure SVG path-data tokenizer (docs/08 §4) — JVM tests.
 */
class SvgPathParserTest {

    @Test
    fun parseSimpleAbsoluteCommands() {
        val cmds = SvgPathParser.parse("M10 10 L100 100 Z")!!
        assertEquals(3, cmds.size)
        assertEquals('M', cmds[0].op)
        assertEquals(10f, cmds[0].args[0])
        assertEquals(100f, cmds[1].args[1])
        assertEquals('Z', cmds[2].op)
    }

    @Test
    fun parseRelativeCommands() {
        val cmds = SvgPathParser.parse("m10 10 l5 5")!!
        assertEquals(2, cmds.size)
        assertEquals('m', cmds[0].op)
        assertEquals('l', cmds[1].op)
    }

    @Test
    fun implicitRepetition_mBecomesL() {
        val cmds = SvgPathParser.parse("M10 10 20 20 30 30")!!
        assertEquals(3, cmds.size)
        assertEquals('M', cmds[0].op)
        assertEquals('L', cmds[1].op)
        assertEquals('L', cmds[2].op)
    }

    @Test
    fun cubicAndQuadratic() {
        val cmds = SvgPathParser.parse("M0 0 C1 1 2 2 3 3 Q4 4 5 5")!!
        assertEquals(3, cmds.size)
        assertEquals(6, cmds[1].args.size)
        assertEquals(4, cmds[2].args.size)
    }

    @Test
    fun repeatedCubicArguments_formMultipleCommands() {
        val cmds = SvgPathParser.parse("M0 0 C1 1 2 2 3 3 4 4 5 5 6 6")!!
        assertEquals(3, cmds.size)
        assertEquals('C', cmds[1].op)
        assertEquals('C', cmds[2].op)
    }

    @Test
    fun horizontalAndVertical() {
        val cmds = SvgPathParser.parse("M10 10 H20 V30")!!
        assertEquals(3, cmds.size)
        assertEquals(1, cmds[1].args.size)
        assertEquals(1, cmds[2].args.size)
    }

    @Test
    fun commasAndWhitespace_areTolerated() {
        val cmds = SvgPathParser.parse("M10,10 L20,20 30,30")!!
        assertEquals(3, cmds.size)
    }

    @Test
    fun malformedInput_returnsNull() {
        assertNull(SvgPathParser.parse(""))
        assertNull(SvgPathParser.parse("M10"))           // command without args
        assertNull(SvgPathParser.parse("L10 10"))        // number before command
        assertNull(SvgPathParser.parse("not a path"))
        assertNull(SvgPathParser.parse("M10 10 X 20 20")) // unknown command
    }
}
