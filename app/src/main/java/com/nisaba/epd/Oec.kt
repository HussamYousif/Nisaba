package com.nisaba.epd

import android.os.IBinder
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runtime e-ink waveform control via the OEC binder service
 * (`android.onyx.optimization.IOECService`, hosted in the framework).
 *
 * `setAppScopeRefreshMode(int, boolean)` switches the CALLER app's refresh
 * waveform — the same lever Boox's own writing apps use. The service is
 * reached through the boot-classpath interface + reflection; every call is
 * defensive (no-ops anywhere the service is absent or uncooperative).
 *
 * Leaf: no dependencies inside the app.
 */
object Oec {

    private const val TAG = "NisabaOec"

    /** Fast waveform while the pen is down. */
    const val MODE_FAST = 2

    /** Normal waveform when idle (the static App Optimization setting). */
    const val MODE_NORMAL = 0

    private var proxy: Any? = null
    private var setter: java.lang.reflect.Method? = null
    private val current = AtomicInteger(-1)

    @Synchronized
    private fun ensureInit() {
        if (proxy != null) return
        proxy = runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java)
                .invoke(null, "oec_service") as IBinder
            val stub = Class.forName("android.onyx.optimization.IOECService\$Stub")
            val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            setter = service.javaClass.methods.firstOrNull { it.name == "setAppScopeRefreshMode" }
            Log.i(TAG, "OEC service bound, setter=${setter != null}")
            service
        }.getOrNull()
        if (proxy == null) Log.i(TAG, "OEC service unavailable")
    }

    /** Switches the app's refresh waveform; deduplicated, never throws. */
    fun setRefreshMode(mode: Int) {
        if (current.getAndSet(mode) == mode) return
        ensureInit()
        val s = setter ?: return
        val p = proxy ?: return
        runCatching { s.invoke(p, mode, false) }
            .onFailure { Log.w(TAG, "setRefreshMode($mode) failed: ${it.cause?.message ?: it.message}") }
    }
}
