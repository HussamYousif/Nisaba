package com.nisaba.device

import android.graphics.Rect
import android.os.IBinder
import android.util.Log
import android.view.View
import java.lang.reflect.Method

/**
 * Refresh waveform via the framework OEC binder (`oec_service` → `IOECService.setAppScopeRefreshMode`).
 * Verified to exist on the Air 5C (fw 4.2.1, Android 15) in the first attempt; mode values are provisional
 * (0 = normal, 2 = fast) until spike S3 is measured with eyes on the panel.
 */
class OnyxEinkDevice : EinkDevice {
    private var service: Any? = null
    private var setter: Method? = null
    private var current = -1

    /** The mode the screen asked for (FAST while writing); a clean pass borrows NORMAL for one frame, then gives it back. */
    private var wanted = MODE_NORMAL

    override fun fast() { wanted = MODE_FAST; set(MODE_FAST) }
    override fun normal() { wanted = MODE_NORMAL; set(MODE_NORMAL) }

    override fun cleanPass(view: View, rect: Rect?) {
        set(MODE_NORMAL)
        if (rect == null) view.invalidate() else view.invalidate(rect)
        restoreAfterFrame(view)
    }

    override fun fullRefresh(view: View) {
        set(MODE_NORMAL)
        view.invalidate()
        restoreAfterFrame(view)
    }

    /** Back to what was wanted once the clean frame is on the panel (it used to stay NORMAL, slowing the pen). */
    private fun restoreAfterFrame(view: View) {
        if (wanted == MODE_NORMAL) return
        view.postDelayed({ set(wanted) }, CLEAN_FRAME_MILLIS)
    }

    @Synchronized private fun set(mode: Int) {
        if (current == mode) return
        current = mode
        runCatching {
            if (service == null) bind()
            setter?.invoke(service, mode, false)
        }.onFailure { Log.w(TAG, "refresh mode $mode failed: ${it.cause?.message ?: it.message}") }
    }

    @Suppress("PrivateApi", "DiscouragedPrivateApi")
    private fun bind() {
        val getService = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "oec_service") as IBinder
        val stub = Class.forName("android.onyx.optimization.IOECService\$Stub")
        service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        setter = service?.javaClass?.methods?.firstOrNull { it.name == "setAppScopeRefreshMode" }
        Log.i(TAG, "OEC bound, setter=${setter != null}")
    }

    companion object {
        private const val TAG = "NisabaEink"
        const val MODE_NORMAL = 0
        const val MODE_FAST = 2
        private const val CLEAN_FRAME_MILLIS = 400L
    }
}
