package com.nisaba.notebook

/**
 * The summon detection (docs/02 F3, docs/09 §3): fires on 3 s of writing
 * silence (only when there is new content), cancels the in-flight request the
 * moment the user writes again (cancel-on-resume), and supports the manual
 * quill summon. Pure detection: the notebook screen owns the actual request.
 *
 * Depends on [SaveScheduler], not android.os, so the whole state machine is
 * JVM-testable (audit P3-1).
 */
class SummonController(
    private val scheduler: SaveScheduler,
    private val idleTimeoutSeconds: () -> Int,
    private val autoSummonEnabled: () -> Boolean,
) {

    interface Listener {
        /** Idle fired and there is new content to answer. */
        fun onIdleSummon()

        /** Quill button pressed. */
        fun onManualSummon()

        /** The user resumed writing while a request was pending. */
        fun onRequestCancelledByWriting()
    }

    var listener: Listener? = null

    /** True when strokes changed since the last summon (F3.4). */
    var hasNewContent: () -> Boolean = { false }

    private var cancelPending: (() -> Unit)? = null

    private val idleRunnable = Runnable {
        if (autoSummonEnabled() && hasNewContent()) {
            listener?.onIdleSummon()
        }
    }

    /** Every new stroke point: cancel a pending request and re-arm the timer. */
    fun onStrokePoint() {
        val canceller = cancelPending
        if (canceller != null) {
            cancelPending = null
            canceller()
            listener?.onRequestCancelledByWriting()
        }
        scheduler.cancel(idleRunnable)
        scheduler.schedule(idleRunnable, idleTimeoutSeconds().toLong() * 1000L)
    }

    /** Manual summon: cancels anything pending and fires immediately. */
    fun manualSummon() {
        cancelPending?.invoke()
        cancelPending = null
        scheduler.cancel(idleRunnable)
        listener?.onManualSummon()
    }

    /** Cancels a pending request + idle timer without firing any listener. */
    fun cancelAll() {
        cancelPending?.invoke()
        cancelPending = null
        scheduler.cancel(idleRunnable)
    }

    /** Registers the in-flight request so writing cancels it (F3.2). */
    fun setPendingRequest(canceller: () -> Unit) {
        cancelPending = canceller
    }

    fun clearPendingRequest() {
        cancelPending = null
    }

    fun shutdown() {
        scheduler.cancel(idleRunnable)
        cancelPending = null
    }
}
