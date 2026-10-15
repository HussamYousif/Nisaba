package com.nisaba.domain.answer

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnswerParserTest {
    private fun run(chunks: List<String>): List<ParseEvent> {
        val p = AnswerParser()
        return chunks.flatMap { p.feed(it) } + p.finish()
    }

    private fun whole(s: String) = run(listOf(s))
    private fun blocks(s: String) = whole(s).filterIsInstance<ParseEvent.BlockReady>().map { it.block }
    private fun t(s: String, bold: Boolean = false) = Inline.Text(s, bold)

    private val fixtures = listOf(
        "<silent/><heard>Buy milk</heard>",
        "<answer>\nOslo.\n</answer><heard>Capital of Norway?</heard>",
        "<answer>\nFirst paragraph\nsame paragraph.\n\nSecond paragraph.\n</answer><heard>q</heard>",
        "<answer>\n## Steps\n- one\n- two with **bold**\n  continued\n1. first\n2) second\n</answer><heard>steps?</heard>",
        "<answer>\n\$\$x = \\frac{-b}{2a}\$\$\n\nInline \$a^2+b^2\$ here.\n</answer><heard>x</heard>",
        "<answer>\n\$\$\nx = 1\n\$\$\n</answer><heard>m</heard>",
        "<answer>\n| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n\nafter\n</answer><heard>t</heard>",
        "<answer>\n| not | a table |\ntext\n</answer><heard>t</heard>",
        "<answer>\n```diagram\n<svg viewBox=\"0 0 1000 1000\"><circle cx=\"500\" cy=\"500\" r=\"100\"/></svg>\n" +
            "Sun @ 480,300\n```\nDone.\n</answer><heard>draw</heard>",
        "no tags at all, just an answer\n\nwith two paragraphs",
        "<answer>\nmissing close tag\n\nsecond\n",
        "<answer>\nlate heard\n<heard>oops</heard>",
        "  \n<answer>\n---\ntext\n</answer>\n<heard> spaced </heard>\n",
        "<answer>\n```diagram\nunterminated fence\n</answer><heard>x</heard>",
        "<answer>\nNoted.\n<remember category=\"me\">Is a nurse</remember>\nMore.\n</answer><remember>x</remember><heard>h</heard>",
        "<heard>asked first</heard>\n<answer>\nAnswer.\n</answer>",
        "<silent /><heard>n</heard>",
        "<Silent></Silent><heard>n</heard>",
        "<silent>\n<heard>n</heard>",
        "<answer>\n```diagram\n<svg viewBox=\"0 0 100 100\"><line x1=\"0\" y1=\"0\" x2=\"9\" y2=\"9\"/><circle cx=\"5",
        "<answer>\nHi.\n<thinking/>\n</answer><heard>h</heard>",
        "<answer>\nLike so:\n```python\ndef double(x):\n\n    return x * 2\n```\nDone.\n</answer><heard>c</heard>",
        "<answer>\n```kotlin\nfun main() {\n    println(1)",
    )

    @Test fun fencedCodeKeepsItsLinesAndIndentation() {
        assertEquals(
            listOf(
                Block.Paragraph(listOf(t("Like so:"))),
                Block.Code("python", listOf("def double(x):", "", "    return x * 2")),
                Block.Paragraph(listOf(t("Done."))),
            ),
            blocks(fixtures[21]),
        )
        assertEquals(Block.Code("kotlin", listOf("fun main() {", "    println(1)")), blocks(fixtures[22]).single()) // cut off
    }

    @Test fun heardBeforeTheAnswerKeepsTheAnswer() {
        val events = whole(fixtures[15])
        assertEquals(ParseEvent.Heard("asked first"), events.first())
        assertEquals(listOf(Block.Paragraph(listOf(t("Answer.")))), events.filterIsInstance<ParseEvent.BlockReady>().map { it.block })
    }

    @Test fun silentIsRecognisedHoweverItIsSpelled() {
        for (f in fixtures.subList(16, 19)) assertEquals(listOf(ParseEvent.Silent, ParseEvent.Heard("n")), whole(f), f)
    }

    @Test fun aCutOffDiagramIsDrawnNotWritten() {
        val d = blocks(fixtures[19]).single() as Block.Diagram
        assertTrue(d.svg.endsWith("</svg>"))
        assertEquals(1, parseDiagram(d.svg)!!.shapes.size) // the complete line; the half-sent circle is dropped
    }

    @Test fun lessonTopicTagsAreNeverInk() {
        assertEquals(
            listOf(Block.Paragraph(listOf(t("Right.")))),
            blocks("<answer>\nRight. <topic status=\"learned\">Fjords</topic>\n</answer><topic status=\"shaky\">X</topic><heard>h</heard>"),
        )
    }

    @Test fun aLoneTagLineIsNotInk() {
        assertEquals(listOf(Block.Paragraph(listOf(t("Hi.")))), blocks(fixtures[20]))
    }

    @Test fun silentReportsSilentThenHeard() {
        assertEquals(listOf(ParseEvent.Silent, ParseEvent.Heard("Buy milk")), whole("<silent/><heard>Buy milk</heard>"))
    }

    @Test fun memoryTagsAreNeverInkEvenInsideTheAnswer() {
        val events = whole(
            "<answer>\nNoted. <forget id=\"2\"/>\n<remember category=\"me\">Is a nurse</remember>\nMore.\n</answer>" +
                "<remember category=\"books\">Dune</remember><heard>I'm a nurse</heard>",
        )
        val blocks = events.filterIsInstance<ParseEvent.BlockReady>().map { it.block }
        assertEquals(listOf(Block.Paragraph(listOf(t("Noted. More.")))), blocks)
        assertEquals(ParseEvent.Heard("I'm a nurse"), events.last())
    }

    @Test fun simpleAnswer() {
        assertEquals(
            listOf(ParseEvent.BlockReady(Block.Paragraph(listOf(t("Oslo.")))), ParseEvent.Heard("Capital of Norway?")),
            whole("<answer>\nOslo.\n</answer><heard>Capital of Norway?</heard>"),
        )
    }

    @Test fun paragraphsJoinLinesAndSplitOnBlankLines() {
        val b = blocks(fixtures[2])
        assertEquals(
            listOf(Block.Paragraph(listOf(t("First paragraph same paragraph."))), Block.Paragraph(listOf(t("Second paragraph.")))),
            b,
        )
    }

    @Test fun headingsListsAndContinuations() {
        val b = blocks(fixtures[3])
        assertEquals(Block.Heading(2, listOf(t("Steps"))), b[0])
        assertEquals(Block.ListItem(false, "-", 0, listOf(t("one"))), b[1])
        assertEquals(Block.ListItem(false, "-", 0, listOf(t("two with "), t("bold", true), t(" continued"))), b[2])
        assertEquals(Block.ListItem(true, "1.", 0, listOf(t("first"))), b[3])
        assertEquals(Block.ListItem(true, "2)", 0, listOf(t("second"))), b[4])
    }

    @Test fun mathDisplayAndInline() {
        val b = blocks(fixtures[4])
        assertEquals(Block.MathBlock("x = \\frac{-b}{2a}"), b[0])
        assertEquals(listOf(t("Inline "), Inline.Math("a^2+b^2"), t(" here.")), (b[1] as Block.Paragraph).inlines)
        assertEquals(Block.MathBlock("x = 1"), blocks(fixtures[5]).single())
    }

    @Test fun tableNeedsASeparatorRow() {
        val b = blocks(fixtures[6])
        val table = b[0] as Block.Table
        assertEquals(listOf(listOf(t("a")), listOf(t("b"))), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(Block.Paragraph(listOf(t("after"))), b[1])
        assertTrue(blocks(fixtures[7]).all { it is Block.Paragraph }, "no separator → plain paragraphs")
    }

    @Test fun diagramWithLabels() {
        val d = blocks(fixtures[8])[0] as Block.Diagram
        assertTrue(d.svg.startsWith("<svg") && d.svg.endsWith("</svg>"))
        assertEquals(listOf(DiagramLabel("Sun", 480, 300)), d.labels)
    }

    @Test fun leniencyForForgottenTagsAndOddEndings() {
        assertEquals(2, blocks(fixtures[9]).size)
        assertEquals(2, blocks(fixtures[10]).size)                        // missing </answer> and <heard>
        assertEquals(ParseEvent.Heard(""), whole(fixtures[10]).last())
        assertEquals(ParseEvent.Heard("oops"), whole(fixtures[11]).last())   // <heard> ends the answer
        assertEquals(listOf(Block.Rule, Block.Paragraph(listOf(t("text")))), blocks(fixtures[12]))
        assertEquals(ParseEvent.Heard("spaced"), whole(fixtures[12]).last())
        assertTrue(blocks(fixtures[13]).single() is Block.Paragraph)      // unterminated fence degrades to text
    }

    @Test fun blocksAreReportedBeforeTheStreamEnds() {
        val p = AnswerParser()
        val events = p.feed("<answer>\nFirst.\n\nSecond, still writ")
        assertEquals(listOf(ParseEvent.BlockReady(Block.Paragraph(listOf(t("First."))))), events)
        assertTrue(p.feed("ing").isEmpty())
    }

    @Test fun exactlyOneHeardEventEvenForGarbage() {
        for (s in fixtures + listOf("", "<", "<ans", "<answer>", "<silent/>")) {
            assertEquals(1, whole(s).count { it is ParseEvent.Heard }, "input=$s")
        }
    }

    @Test fun chunkingNeverChangesTheResult() {
        val rnd = Random(2026)
        for (f in fixtures) {
            val expected = whole(f)
            assertEquals(expected, run(f.map { it.toString() }), "per-char: $f")
            repeat(500) {
                val cuts = (0 until rnd.nextInt(1, 8)).map { rnd.nextInt(0, f.length + 1) }.distinct().sorted()
                val chunks = (listOf(0) + cuts + listOf(f.length)).zipWithNext { a, b -> f.substring(a, b) }
                assertEquals(expected, run(chunks), "chunks=$chunks")
            }
        }
    }

    @Test fun unicodeSubAndSuperscriptsBecomeMathTheHandCanWrite() {
        assertEquals(
            listOf(Inline.Text("Water ("), Inline.Math("H_{2}O"), Inline.Text(")")),
            parseInlines("Water (H₂O)"),
        )
        val d = "$"
        assertEquals("${d}C_{6}H_{12}O_{6}$d and ${d}x^{2}$d and ${d}10^{-3}$d", scriptsToMath("C₆H₁₂O₆ and x² and 10⁻³"))
        assertEquals("already ${d}H_2O$d math", scriptsToMath("already ${d}H_2O$d math"))
    }

    @Test fun marksBesideCheckedLinesAreTheirOwnBlocks() {
        val reply = "<answer>\n<mark line=\"1\" ok=\"true\"/>\n<mark line=\"2\" ok=\"false\">−3, not +3</mark>\n" +
            "<mark ok='yes' line='3'/>\nNearly: one sign slip.\n</answer><heard>2x+3=7</heard>"
        val expected = listOf(
            Block.Mark(1, true, ""), Block.Mark(2, false, "−3, not +3"), Block.Mark(3, true, ""),
            Block.Paragraph(listOf(t("Nearly: one sign slip."))),
        )
        assertEquals(expected, blocks(reply))
        assertEquals(expected, run(reply.chunked(5)).filterIsInstance<ParseEvent.BlockReady>().map { it.block }) // streamed
        // a mark-looking line inside code stays code; an unrelated tag is no mark
        assertEquals(null, parseMark("<marker line=\"1\"/>"))
        assertEquals(null, parseMark("<mark ok=\"true\"/>"))
    }
}
