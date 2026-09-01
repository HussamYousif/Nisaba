package com.nisaba.epd

import android.app.Activity
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.ink.TestActivity
import org.junit.Assert.assertTrue
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
                // No crash above is the assertion; availability is informational.
                assertTrue(true)
            }
        }
    }
}
