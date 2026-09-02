package com.nisaba.render

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.notebook.model.AnswerBlock
import com.nisaba.notebook.model.AnswerGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AnswerInk layout (docs/08 §1): answer groups become page-space ink —
 * wrapped text lines, scaled drawing paths, ruled tables — positioned below
 * the last content.
 */
@RunWith(AndroidJUnit4::class)
class AnswerInkTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    init {
        com.nisaba.magic.Fonts.load(ctx.assets)
    }

    private fun group(vararg blocks: AnswerBlock) = AnswerGroup(
        id = "a1",
        status = "complete",
        blocks = blocks.toList(),
    )

    @Test
    fun textBlock_wrapsIntoLines() {
        val g = group(AnswerBlock(type = "text", markdown = "**Hello** from the scribe. " + "word ".repeat(60)))
        val layout = AnswerInk.layout(g, 2480f, 500f, ctx)
        assertTrue("lines: ${layout.itemCount}", layout.itemCount > 3)
        assertTrue(layout.heightPx > 0f)
        assertTrue(layout.items.all { it.baselineOrY() >= 500f })
    }

    @Test
    fun drawingBlock_scalesIntoPageSpace() {
        val g = group(
            AnswerBlock(
                type = "drawing",
                svg = "<svg viewBox='0 0 1000 1000'><path d='M100 100 L900 900' stroke='#3A2E25' stroke-width='8'/>" +
                    "<circle cx='500' cy='400' r='200' fill='#3A2E25'/></svg>",
            )
        )
        val layout = AnswerInk.layout(g, 2480f, 500f, ctx)
        val paths = layout.items.filterIsInstance<AnswerInk.Layout.Item.Path>()
        assertEquals(2, paths.size)
        for (p in paths) {
            val bounds = android.graphics.RectF()
            p.path.computeBounds(bounds, true)
            assertTrue("path in page space", bounds.width() > 0f && bounds.height() > 0f)
        }
    }

    @Test
    fun tableBlock_producesRulesAndCells() {
        val g = group(
            AnswerBlock(
                type = "table",
                title = "Incantations",
                headers = listOf("Spell", "Effect"),
                rows = listOf(listOf("Lumos", "Light"), listOf("Nox", "Darkness"), listOf("A", "B"), listOf("C", "D"), listOf("E", "F")),
            )
        )
        val layout = AnswerInk.layout(g, 2480f, 500f, ctx)
        val rules = layout.items.filterIsInstance<AnswerInk.Layout.Item.Rule>()
        val lines = layout.items.filterIsInstance<AnswerInk.Layout.Item.Line>()
        // header double rule + horizontal rule every 4 rows + vertical rules + flourishes
        assertTrue("rules: ${rules.size}", rules.size >= 6)
        assertTrue("cells: ${lines.size}", lines.size >= 8)
        assertTrue(lines.any { it.text == "Incantations" })
    }

    @Test
    fun markdownMarkers_areStripped() {
        assertEquals("Hello world", AnswerInk.plain("**Hello** *world*"))
    }


    @Test
    fun textBlock_writesAsPenStrokes() {
        // docs/08 §3: prose is scribed as pen strokes, not font glyphs.
        val g = group(AnswerBlock(type = "text", markdown = "**The ink answers.** Well met."))
        val layout = AnswerInk.layout(g, 2480f, 500f, ctx)
        val strokes = layout.items.filterIsInstance<AnswerInk.Layout.Item.Stroke>()
        assertTrue("strokes: " + strokes.size, strokes.size >= 5)
        // no font-rendered words remain (flourish glyphs are single chars)
        assertTrue(layout.items.none { it is AnswerInk.Layout.Item.Line && it.text.length > 1 })
        for (s in strokes) {
            assertTrue("reveal ticks", s.revealTicks in 1..20)
            assertTrue("polyline", s.pointCount >= 2)
            assertTrue("arc", s.length > 0f)
            for (i in 0 until s.pointCount) {
                val x = s.xys[i * 2]
                val y = s.xys[i * 2 + 1]
                assertTrue("x in page", x in 60f..2420f)
                assertTrue("y below start", y >= 500f - 5f)
                assertTrue("width sane", s.widths[i] in 0.5f..30f)
            }
        }
    }

    @Test
    fun longText_wrapsIntoMultipleStrokeLines() {
        val g = group(AnswerBlock(type = "text", markdown = "word ".repeat(80)))
        val layout = AnswerInk.layout(g, 2480f, 500f, ctx)
        val strokes = layout.items.filterIsInstance<AnswerInk.Layout.Item.Stroke>()
        // 80 words cannot sit on one line; heights must grow accordingly
        assertTrue("many strokes: " + strokes.size, strokes.size > 40)
        assertTrue(layout.heightPx > 300f)
    }

    @Test
    fun sameGroupId_scribesIdentically() {
        // A saved answer must re-ink identically on reopen.
        val md = "The quick brown fox jumps over the lazy dog."
        val a = AnswerInk.layout(group(AnswerBlock(type = "text", markdown = md)), 2480f, 500f, ctx)
        val b = AnswerInk.layout(group(AnswerBlock(type = "text", markdown = md)), 2480f, 500f, ctx)
        val sa = a.items.filterIsInstance<AnswerInk.Layout.Item.Stroke>()
        val sb = b.items.filterIsInstance<AnswerInk.Layout.Item.Stroke>()
        assertEquals(sa.size, sb.size)
        for (i in sa.indices) {
            assertTrue(sa[i].xys.contentEquals(sb[i].xys))
            assertTrue(sa[i].widths.contentEquals(sb[i].widths))
        }
    }

    private fun AnswerInk.Layout.Item.baselineOrY(): Float = when (this) {
        is AnswerInk.Layout.Item.Line -> baselineY
        is AnswerInk.Layout.Item.Path -> Float.MAX_VALUE
        is AnswerInk.Layout.Item.Rule -> y1
        is AnswerInk.Layout.Item.Stroke -> xys[1]
    }
}
