package com.nisaba.infra.log

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashLogStoreTest {
    private val dir = Files.createTempDirectory("crash").toFile()

    @Test fun keepsTheLastCrashUntilCleared() {
        val log = CrashLogStore(dir)
        assertNull(log.last())
        log.write("0.1.0 debug", "main", IllegalStateException("first"), nowMillis = 0)
        log.write("0.1.0 debug", "main", IllegalArgumentException("ink went missing"), nowMillis = 0)
        val text = log.last()!!
        assertTrue(text.startsWith("Nisaba 0.1.0 debug · 1970-01-01 "), text)
        assertTrue("IllegalArgumentException: ink went missing" in text && "first" !in text, text)
        assertEquals(listOf("last.txt"), dir.list()!!.toList()) // nothing left aside
        log.clear()
        assertNull(log.last())
    }

    @Test fun theHandlerRecordsThenHandsOn() {
        val log = CrashLogStore(dir)
        var handedOn: Throwable? = null
        val before = Thread.getDefaultUncaughtExceptionHandler()
        try {
            log.install("0.1.0 release") { _, e -> handedOn = e }
            val boom = RuntimeException("boom")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
            assertEquals(boom, handedOn)
            assertTrue("RuntimeException: boom" in log.last()!!)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }
}
