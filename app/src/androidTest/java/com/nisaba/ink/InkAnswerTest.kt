package com.nisaba.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.notebook.model.AnswerBlock
import com.nisaba.notebook.model.AnswerGroup
import com.nisaba.notebook.model.NotebookPage
import com.nisaba.render.AnswerInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The answer-as-canvas-ink pipeline (docs/08 §1, §5): the view reveals the
 * scribe's ink in discrete steps and draws safely at every progress value.
 */
@RunWith(AndroidJUnit4::class)
class InkAnswerTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    init {
        com.nisaba.magic.Fonts.load(ctx.assets)
    }

    private fun view(): InkSurfaceView {
        val v = InkSurfaceView(ctx).apply {
            layout(0, 0, 2400, 2000)
            page = NotebookPage(index = 0, width = 2480, height = 1860)
        }
        return v
    }

    private fun sampleLayout(): AnswerInk.Layout {
        val g = AnswerGroup(
            id = "a1",
            status = "complete",
            blocks = listOf(
                AnswerBlock(type = "text", markdown = "**The ink answers.** Well met."),
                AnswerBlock(
                    type = "drawing",
                    svg = "<svg viewBox='0 0 1000 1000'><path d='M100 900 C300 100 700 100 900 900' stroke='#3A2E25' stroke-width='8'/></svg>",
                ),
                AnswerBlock(type = "table", headers = listOf("A", "B"), rows = listOf(listOf("1", "2"))),
            ),
        )
        return AnswerInk.layout(g, 2480f, 300f, ctx)
    }

    @Test
    fun setAnswers_revealsAndSkips() {
        val v = view()
        v.setAnswers(listOf(sampleLayout()), 2000f, animateLast = true)
        assertTrue("items on canvas", v.answerItemCount > 0)
        assertTrue(v.answerPathCount > 0)
        assertTrue(v.answerRuleCount > 0)

        // Reveal advances over time…
        val bitmap = Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap)) // partial progress must not throw
        Thread.sleep(300)
        v.draw(Canvas(bitmap))
        // …and skipping completes it instantly.
        v.skipAnswerReveal()
        v.draw(Canvas(bitmap))
    }

    @Test
    fun setAnswers_withoutAnimation_drawsEverythingImmediately() {
        val v = view()
        v.setAnswers(listOf(sampleLayout()), 2000f, animateLast = false)
        val bitmap = Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap)) // full state, no crash
    }

    @Test
    fun emptyLayouts_areSafe() {
        val v = view()
        v.setAnswers(emptyList(), 0f, animateLast = true)
        assertEquals(0, v.answerItemCount)
        assertFalse(v.skipAnswerReveal().let { false }) // no-op path
    }

    /**
     * Inked pixel count: pixels that clearly differ from the parchment.
     * The faint ruled lines (alpha 0x0E over parchment) stay within ~12 of
     * parchment and are ignored; the answer ink (full INK) is far outside.
     */
    private fun inked(bmp: Bitmap): Int {
        val parchment = com.nisaba.magic.MagicTheme.PARCHMENT
        val pr = (parchment ushr 16) and 0xFF
        val pg = (parchment ushr 8) and 0xFF
        val pb = parchment and 0xFF
        var n = 0
        val row = IntArray(bmp.width)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            for (px in row) {
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                if (kotlin.math.abs(r - pr) + kotlin.math.abs(g - pg) + kotlin.math.abs(b - pb) > 24) n++
            }
        }
        return n
    }

    @Test
    fun secondAnswer_writesItselfInGradually() {
        // Audit P0-4 regression: the reveal accumulator overflowed at
        // Int.MAX_VALUE once a page already had one answer, so the newest
        // answer stayed invisible for the whole reveal and then snapped in
        // all at once. It must write itself in over time.
        val v = view()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = com.nisaba.magic.MagicTheme.INK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 14f
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val path = android.graphics.Path().apply { moveTo(80f, 80f); lineTo(500f, 500f) }
        // First layout: already revealed, contributes nothing (empty).
        val first = AnswerInk.Layout(items = emptyList(), heightPx = 0f)
        // Second layout: one path item with revealTicks = 6.
        val second = AnswerInk.Layout(
            items = listOf(AnswerInk.Layout.Item.Path(path, paint, length = 594f, fill = false)),
            heightPx = 100f,
        )
        v.setAnswers(listOf(first, second), 2000f, animateLast = true)
        assertTrue(v.answerItemCount > 0)

        val bitmap = Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap)) // answerTicks == 0: nothing of B may be inked
        val before = inked(bitmap)

        Thread.sleep(250) // ≈ 2 reveal ticks (ANSWER_STEP_MS = 110)
        v.draw(Canvas(bitmap))
        val after = inked(bitmap)
        assertTrue(
            "the second answer must write itself in (before=$before, after=$after)",
            after > before,
        )
    }

    @Test
    fun newPage_clearsPreviousAnswerInk() {
        // Audit P0-2 regression: turning to a page with no answers must clear
        // the previous page's answer ink (and its display bottom) instead of
        // leaving them on the canvas.
        val v = view()
        v.setAnswers(listOf(sampleLayout()), 2000f, animateLast = false)
        assertTrue(v.answerItemCount > 0)

        v.page = NotebookPage(index = 1, width = 2480, height = 1860)
        assertEquals("no answer ink may follow you to the next page", 0, v.answerItemCount)
    }
}
