package com.nisaba.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SummonController (docs/02 F3, docs/09 §3) — the core-loop state machine,
 * now JVM-testable via the injected SaveScheduler (audit P3-1): idle fires
 * only with new content, writing cancels the pending request, the manual
 * quill cancels and fires immediately, and shutdown/cancelAll stay quiet.
 */
class SummonControllerTest {

    private class FakeScheduler : SaveScheduler {
        val scheduled = mutableListOf<Pair<Runnable, Long>>()
        val cancelled = mutableListOf<Runnable>()
        override fun schedule(task: Runnable, delayMs: Long) {
            scheduled.add(task to delayMs)
        }
        override fun cancel(task: Runnable) {
            cancelled.add(task)
        }
    }

    private lateinit var scheduler: FakeScheduler
    private lateinit var controller: SummonController
    private val events = mutableListOf<String>()
    private var autoSummon = true
    private var hasNew = false
    private var cancelledByWriting = false
    private var cancelledCount = 0

    @Before
    fun setUp() {
        scheduler = FakeScheduler()
        controller = SummonController(
            scheduler = scheduler,
            idleTimeoutSeconds = { 3 },
            autoSummonEnabled = { autoSummon },
        ).apply {
            listener = object : SummonController.Listener {
                override fun onIdleSummon() {
                    events.add("idle")
                }
                override fun onManualSummon() {
                    events.add("manual")
                }
                override fun onRequestCancelledByWriting() {
                    cancelledByWriting = true
                    events.add("cancelled-by-writing")
                }
            }
            hasNewContent = { hasNew }
        }
    }

    /** Fires the scheduled idle runnable, as the looper would. */
    private fun fireIdle() {
        val last = scheduler.scheduled.lastOrNull() ?: error("idle timer never scheduled")
        last.first.run()
    }

    @Test
    fun strokePoint_armsTheIdleTimer() {
        controller.onStrokePoint()
        assertEquals(1, scheduler.scheduled.size)
        assertEquals(3_000L, scheduler.scheduled[0].second)
        assertTrue(events.isEmpty())
    }

    @Test
    fun idleFires_onlyWithNewContent_andEnabled() {
        controller.onStrokePoint()
        fireIdle()
        assertTrue("no new content → no summon", events.isEmpty())

        hasNew = true
        controller.onStrokePoint() // re-arm after the first fire
        fireIdle()
        assertEquals(listOf("idle"), events)
    }

    @Test
    fun idleDoesNotFire_whenAutoSummonDisabled() {
        autoSummon = false
        hasNew = true
        controller.onStrokePoint()
        fireIdle()
        assertTrue("disabled auto-summon must stay silent", events.isEmpty())
    }

    @Test
    fun writingWhilePending_cancelsTheRequest() {
        var cancelled = false
        controller.setPendingRequest { cancelled = true }
        controller.onStrokePoint()

        assertTrue("pending request must be cancelled on the next stroke", cancelled)
        assertTrue(cancelledByWriting)
        // The canceller is consumed — a further stroke cancels nothing new.
        cancelled = false
        controller.onStrokePoint()
        assertFalse(cancelled)
    }

    @Test
    fun clearPendingRequest_disarmsTheCanceller() {
        var cancelled = false
        controller.setPendingRequest { cancelled = true }
        controller.clearPendingRequest()
        controller.onStrokePoint()
        assertFalse("cleared request must not be cancelled", cancelled)
    }

    @Test
    fun manualSummon_cancelsPending_andFiresImmediately() {
        var cancelled = false
        controller.setPendingRequest { cancelled = true }
        controller.manualSummon()
        assertTrue(cancelled)
        assertEquals(listOf("manual"), events)
        assertTrue("idle timer must be disarmed", scheduler.cancelled.isNotEmpty())
    }

    @Test
    fun cancelAll_isSilent() {
        var cancelled = false
        controller.setPendingRequest { cancelled = true }
        controller.cancelAll()
        assertTrue(cancelled)
        assertTrue("cancelAll must not fire any listener", events.isEmpty())
    }

    @Test
    fun shutdown_disarmsEverything() {
        var cancelled = false
        controller.setPendingRequest { cancelled = true }
        controller.shutdown()
        controller.onStrokePoint() // must not fire the cancelled request
        assertFalse(cancelled)
        assertTrue(events.isEmpty())
    }

    @Test
    fun repeatedStrokePoints_debounceTheTimer() {
        controller.onStrokePoint()
        controller.onStrokePoint()
        controller.onStrokePoint()
        // Each stroke re-arms: one schedule per stroke point, cancels in between.
        assertEquals(3, scheduler.scheduled.size)
        assertEquals(3, scheduler.cancelled.size)
    }
}
