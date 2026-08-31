package com.nisaba.notebook

import android.os.Handler
import android.os.Looper

/**
 * Debounced save scheduling (docs/05 §4). The session depends on this
 * interface, not on android.os — JVM tests inject a synchronous fake.
 */
interface SaveScheduler {
    fun schedule(task: Runnable, delayMs: Long)
    fun cancel(task: Runnable)
}

/** Main-thread implementation used by the app. */
class HandlerSaveScheduler : SaveScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun schedule(task: Runnable, delayMs: Long) {
        handler.postDelayed(task, delayMs)
    }
    override fun cancel(task: Runnable) {
        handler.removeCallbacks(task)
    }
}
