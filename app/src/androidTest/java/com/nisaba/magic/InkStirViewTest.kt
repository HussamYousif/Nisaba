package com.nisaba.magic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ink-stirring indicator (docs/09 §3): stir/settle lifecycle and safe
 * drawing at every step (e-ink discrete-step animation).
 */
@RunWith(AndroidJUnit4::class)
class InkStirViewTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun view(): InkStirView = InkStirView(ctx).apply {
        layout(0, 0, 200, 200)
    }

    @Test
    fun stirThenSettle_lifecycleIsClean() {
        val v = view()
        v.stir()
        Thread.sleep(500)
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp)) // mid-stir draw must not throw
        v.settle()
        v.draw(Canvas(bmp)) // settled pose must not throw
        v.stop()
        assertFalse(v.isShown)
    }

    @Test
    fun settleWithoutStir_isSafe() {
        val v = view()
        v.settle() // no-op path (step 0, not stirring)
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
    }

    @Test
    fun stirIsIdempotent() {
        val v = view()
        v.stir()
        v.stir() // second call must not restart/duplicate the loop
        assertTrue(v.isStirring)
        v.stop()
        assertFalse(v.isStirring)
    }
}
