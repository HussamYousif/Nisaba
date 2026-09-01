package com.nisaba.epd

import android.app.Activity
import android.graphics.Rect
import android.util.Log
import android.view.View

/**
 * E-ink display controller for Onyx/Boox devices (docs/04 §2).
 *
 * Boox firmware ships `com.onyx.android.sdk.api.device.epd.EPDController` in
 * the system framework (the artifact is not in any public repo since Bintray
 * died), so we drive it via reflection — the standard approach for
 * third-party Boox apps. Everything is defensive: on non-Boox devices the
 * calls are no-ops and the app behaves as before.
 *
 * Leaf: no dependencies inside the app.
 */
object Epd {

    private const val TAG = "NisabaEpd"

    private val controller: Class<*>? by lazy {
        runCatching { Class.forName("com.onyx.android.sdk.api.device.epd.EPDController") }
            .onFailure { Log.i(TAG, "EPDController not available — no Boox EPD control") }
            .getOrNull()
    }

    private val updatesMode: Class<*>? by lazy {
        runCatching { Class.forName("com.onyx.android.sdk.api.device.epd.EPDController\$UpdatesMode") }
            .getOrNull()
    }

    val available: Boolean get() = controller != null

    init {
        if (available) Log.i(TAG, "EPDController available — fast e-ink refresh enabled")
    }

    /** Fast (A2-like) refresh for the view while the pen is down. */
    fun enableFastMode(view: View) {
        runCatching { controller?.getMethod("enableEpdMode", View::class.java)?.invoke(null, view) }
    }

    fun disableFastMode(view: View) {
        runCatching { controller?.getMethod("disableEpdMode", View::class.java)?.invoke(null, view) }
    }

    /** One clean pass to clear ghosting after a stroke (REACTIVATE/REGAL). */
    fun cleanRefresh(view: View) {
        val c = controller ?: return
        val mode = enumValue("REACTIVATE") ?: return
        runCatching {
            c.getMethod("invalidate", View::class.java, Rect::class.java, updatesMode)
                ?.invoke(null, view, null, mode)
        }
    }

    /** Prevents the system's full-screen flash cycles inside the activity. */
    fun disableFullScreenUpdate(activity: Activity) {
        runCatching {
            controller?.getMethod("disableFullScreenUpdate", Activity::class.java)?.invoke(null, activity)
        }
    }

    private fun enumValue(name: String): Any? =
        updatesMode?.enumConstants?.firstOrNull { (it as Enum<*>).name == name }
}
