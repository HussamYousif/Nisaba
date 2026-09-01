package com.nisaba.magic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.ink.InkSurfaceView
import com.nisaba.notebook.model.NotebookPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The on-page ink-stirring locus (docs/09 §3): the pool draws safely at every
 * discrete step and the begin/end lifecycle in the ink view is clean.
 */
@RunWith(AndroidJUnit4::class)
class InkStirTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun drawIsSafeAtEveryStep() {
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        for (step in 1..12) {
            InkStir.draw(canvas, 150f, 150f, 54f, step, 12) // must not throw
        }
    }

    @Test
    fun inkViewStirLifecycle() {
        val v = InkSurfaceView(ctx).apply {
            layout(0, 0, 800, 800)
            page = NotebookPage(index = 0, width = 2480, height = 1860)
        }
        val bmp = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)

        v.beginStir(400f, 600f)
        assertTrue(v.isStirring)
        Thread.sleep(450)
        v.draw(Canvas(bmp)) // mid-stir draw must not throw

        v.endStir(keep = true)
        assertFalse(v.isStirring)
        v.draw(Canvas(bmp)) // frozen pose must not throw

        v.beginStir(400f, 600f)
        v.endStir(keep = false) // self-clearing path
        assertFalse(v.isStirring)
    }
}
