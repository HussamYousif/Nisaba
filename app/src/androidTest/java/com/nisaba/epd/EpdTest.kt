package com.nisaba.epd

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.ink.TestActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The EPD reflection wrapper must never crash — on non-Boox devices (emulator,
 * tests) every call is a no-op; on Boox it drives the framework's EPD
 * controller (docs/04 §2).
 */
@RunWith(AndroidJUnit4::class)
class EpdTest {

    @Test
    fun callsAreSafeWithoutTheBooxFramework() {
        ActivityScenario.launch(TestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = View(activity)
                Epd.enableFastMode(view)
                Epd.disableFastMode(view)
                Epd.cleanRefresh(view)
                Epd.disableFullScreenUpdate(activity)
                // The calls above are the assertion — no crash on any device.
                // Availability must also be a stable observation across calls
                // (audit P3-4: this used to assert true == true).
                val before = Epd.available
                Epd.enableFastMode(view)
                assertEquals(before, Epd.available)
            }
        }
    }
}
