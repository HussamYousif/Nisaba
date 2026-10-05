package com.nisaba.domain.usage

import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageRulesTest {
    private fun at(y: Int, m: Int, d: Int, h: Int) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test fun peakIsWeekdayMorningsUtc() {
        assertTrue(isPeak(at(2026, 10, 2, 7)))      // Friday 07:00
        assertFalse(isPeak(at(2026, 10, 2, 5)))     // Friday 05:00
        assertFalse(isPeak(at(2026, 10, 3, 7)))     // Saturday
        assertTrue(isPeak(at(2026, 10, 1, 1)))
        assertFalse(isPeak(at(2026, 10, 1, 4)))
    }

    @Test fun cachedPromptTokensAreCheapAndPeakDoublesTheBill() {
        val offPeak = ExchangeUsage("deepseek-flash", at(2026, 10, 3, 12), 1_000_000, 400_000, 100_000)
        assertEquals(400_000 * 0.003e-6 + 600_000 * 0.15e-6 + 100_000 * 0.6e-6, costOf(offPeak)!!, 1e-9)
        assertEquals(2 * costOf(offPeak)!!, costOf(offPeak.copy(createdAtMillis = at(2026, 10, 2, 7)))!!, 1e-9)
        assertNull(costOf(offPeak.copy(model = "someone-else")))
    }

    @Test fun summaryCountsThisMonthAndAllTimeAndSkipsRequestsThatNeverRan() {
        val rows = listOf(
            ExchangeUsage("deepseek-flash", at(2026, 9, 30, 12), 1000, 0, 50),
            ExchangeUsage("deepseek-flash", at(2026, 10, 1, 12), 2000, 1000, 70),
            ExchangeUsage("deepseek-flash", at(2026, 10, 2, 12), 0, 0, 0),     // failed before the model
        )
        val s = summarizeUsage(rows, at(2026, 10, 2, 13), ZoneOffset.UTC)
        assertEquals(1, s.thisMonth.answers); assertEquals(2000, s.thisMonth.inputTokens); assertEquals(1000, s.thisMonth.cachedTokens)
        assertEquals(2, s.allTime.answers); assertEquals(120, s.allTime.outputTokens)
        assertTrue(s.allTime.costUsd!! > s.thisMonth.costUsd!!)
        assertNull(summarizeUsage(rows + ExchangeUsage("mystery", 1, 5, 0, 5), at(2026, 10, 2, 13), ZoneOffset.UTC).allTime.costUsd)
    }
}
