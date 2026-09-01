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

    private fun AnswerInk.Layout.Item.baselineOrY(): Float = when (this) {
        is AnswerInk.Layout.Item.Line -> baselineY
        is AnswerInk.Layout.Item.Path -> Float.MAX_VALUE
        is AnswerInk.Layout.Item.Rule -> y1
    }
}
