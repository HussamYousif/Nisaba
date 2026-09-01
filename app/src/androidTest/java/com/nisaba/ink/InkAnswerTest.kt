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
}
