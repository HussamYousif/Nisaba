package com.nisaba.render

import android.os.Handler

/**
 * Discrete-step reveal sequencer (docs/08 §5): shows one step at a time on a
 * fixed cadence, e-ink-safe (no fades, no smooth motion). Every step can carry
 * a cancel action (e.g. stop a drawing animation) so one tap skips the whole
 * reveal cleanly.
 */
class InkReveal(
    private val handler: Handler,
    private val stepMs: Long = 140L,
) {

    class Step(
        val show: () -> Unit,
        val cancel: (() -> Unit)? = null,
    )

    private var steps: List<Step> = emptyList()
    private var index = 0
    private var onDone: (() -> Unit)? = null

    private val tick = Runnable { advance() }

    fun start(steps: List<Step>, onDone: () -> Unit = {}) {
        this.steps = steps
        this.index = 0
        this.onDone = onDone
        handler.removeCallbacks(tick)
        if (steps.isEmpty()) {
            onDone()
            return
        }
        advance()
    }

    fun isActive(): Boolean = index < steps.size

    fun skip() {
        if (!isActive()) return
        handler.removeCallbacks(tick)
        // Finish everything instantly: remaining shows + their cancels.
        while (index < steps.size) {
            steps[index].show()
            steps[index].cancel?.invoke()
            index++
        }
        finish()
    }

    private fun advance() {
        if (index >= steps.size) {
            finish()
            return
        }
        steps[index].show()
        index++
        if (index < steps.size) {
            handler.postDelayed(tick, stepMs)
        } else {
            finish()
        }
    }

    private fun finish() {
        handler.removeCallbacks(tick)
        val done = onDone
        onDone = null
        steps = emptyList()
        done?.invoke()
    }
}
