package com.nisaba.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.notebook.model.AnswerBlock
import com.nisaba.notebook.model.AnswerGroup
import com.nisaba.notebook.model.NotebookPage
import com.nisaba.notebook.model.Stroke
import com.nisaba.notebook.model.StrokePoint
import com.nisaba.render.AnswerInk
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Dev probe: renders a page with user strokes + a real text answer through
 * the actual view pipeline and saves the PNG for the vision-model feedback
 * loop. Also hard-asserts that the answer region really gets ink.
 */
@RunWith(AndroidJUnit4::class)
class AnswerInkRenderProbeTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun stroke(id: String, points: List<Pair<Float, Float>>): Stroke {
        var t = 0L
        return Stroke(
            id = id,
            width = 3f,
            points = points.map { (x, y) -> StrokePoint(x, y, 0.55f, t++) },
        )
    }

    @Test
    fun answerRegion_getsRealInk() {
        com.nisaba.magic.Fonts.load(ctx.assets)
        val page = NotebookPage(index = 0, width = 2480, height = 1860)
        page.strokes = page.strokes +
            stroke("u1", listOf(400f to 500f, 430f to 430f, 480f to 420f, 510f to 470f, 540f to 500f)) +
            stroke("u2", listOf(700f to 500f, 730f to 420f, 780f to 410f, 820f to 480f, 860f to 500f)) +
            stroke("u3", listOf(900f to 510f, 940f to 480f, 980f to 470f))
        val group = AnswerGroup(
            id = "a-probe-1",
            status = "complete",
            blocks = listOf(
                AnswerBlock(
                    type = "text",
                    markdown = "**The quick brown fox** jumps over the lazy dog. Pack my box with five dozen liquor jugs.",
                ),
            ),
        )
        val y0 = 700f
        val layout = AnswerInk.layout(group, page.width.toFloat(), y0, ctx)
        assertTrue("items", layout.itemCount > 0)

        val view = InkSurfaceView(ctx)
        view.layout(0, 0, 2480, 3200)
        view.page = page
        view.setAnswers(listOf(layout), y0 + layout.heightPx, animateLast = false)
        view.skipAnswerReveal()
        val bmp = Bitmap.createBitmap(2480, 3200, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))

        val parchment = com.nisaba.magic.MagicTheme.PARCHMENT
        val pr = (parchment ushr 16) and 0xFF
        val pg = (parchment ushr 8) and 0xFF
        val pb = parchment and 0xFF
        var inked = 0
        val row = IntArray(bmp.width)
        val fromY = y0.toInt() - 200
        val toY = (y0 + layout.heightPx).toInt() + 100
        for (y in fromY.coerceAtLeast(0) until minOf(toY, bmp.height)) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            for (px in row) {
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                if (kotlin.math.abs(r - pr) + kotlin.math.abs(g - pg) + kotlin.math.abs(b - pb) > 30) inked++
            }
        }
        assertTrue("answer band must be inked (got $inked)", inked > 5000)

        val dir = File(ctx.getExternalFilesDir(null), "probe")
        dir.mkdirs()
        FileOutputStream(File(dir, "answer-probe.png")).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
