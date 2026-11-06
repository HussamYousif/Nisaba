package com.nisaba.device

import android.graphics.Rect
import android.view.View

/** Panel refresh control. Every call is best-effort and must never throw. */
interface EinkDevice {
    /** Fast waveform for writing/animation (some ghosting). */
    fun fast()

    /** Normal waveform (clean text). */
    fun normal()

    /** One clean pass over [rect] (whole view if null) to clear ghosting without a full-screen flash. */
    fun cleanPass(view: View, rect: Rect?)

    /** Full-screen flash refresh (page turns, notebook open). */
    fun fullRefresh(view: View)
}

class NoopEinkDevice : EinkDevice {
    override fun fast() = Unit
    override fun normal() = Unit
    override fun cleanPass(view: View, rect: Rect?) { if (rect == null) view.invalidate() else view.invalidate(rect) }
    override fun fullRefresh(view: View) = view.invalidate()
}
