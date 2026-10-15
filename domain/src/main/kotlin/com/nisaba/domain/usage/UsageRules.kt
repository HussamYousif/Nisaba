package com.nisaba.domain.usage

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Off-peak list prices (DeepSeek pricing page, 2026-10-02); peak hours cost twice as much. */
val MODEL_PRICES: Map<String, ModelPrice> = mapOf(
    "deepseek-flash" to ModelPrice(cacheHit = 0.003, cacheMiss = 0.15, output = 0.6),
    "deepseek-v4-flash" to ModelPrice(cacheHit = 0.003, cacheMiss = 0.15, output = 0.6),
    "deepseek-v4-flash-vision-exp" to ModelPrice(cacheHit = 0.003, cacheMiss = 0.15, output = 0.6),
    "deepseek-v4-pro" to ModelPrice(cacheHit = 0.022, cacheMiss = 0.66, output = 1.98),
)

private const val PER_MILLION = 1_000_000.0
private val PEAK_HOURS_UTC = (1 until 4) + (6 until 10)

/** DeepSeek's peak windows: 01–04 and 06–10 UTC on weekdays (public holidays are not modelled). */
fun isPeak(atMillis: Long): Boolean {
    val t = Instant.ofEpochMilli(atMillis).atZone(ZoneOffset.UTC)
    return t.dayOfWeek != DayOfWeek.SATURDAY && t.dayOfWeek != DayOfWeek.SUNDAY && t.hour in PEAK_HOURS_UTC
}

/** Dollars for one exchange, or null when its model's price is unknown. Prompt tokens include the cached ones. */
fun costOf(u: ExchangeUsage, prices: Map<String, ModelPrice> = MODEL_PRICES): Double? {
    val p = prices[u.model] ?: return null
    val k = if (isPeak(u.createdAtMillis)) 2.0 else 1.0
    val miss = (u.inputTokens - u.cachedTokens).coerceAtLeast(0)
    return k * (u.cachedTokens * p.cacheHit + miss * p.cacheMiss + u.outputTokens * p.output) / PER_MILLION
}

private fun total(rows: List<ExchangeUsage>, prices: Map<String, ModelPrice>): UsageTotals {
    val costs = rows.map { costOf(it, prices) }
    return UsageTotals(
        answers = rows.size,
        inputTokens = rows.sumOf { it.inputTokens.toLong() },
        cachedTokens = rows.sumOf { it.cachedTokens.toLong() },
        outputTokens = rows.sumOf { it.outputTokens.toLong() },
        costUsd = if (costs.any { it == null }) null else costs.sumOf { it ?: 0.0 },
    )
}

/** This calendar month (in [zone]) and all time. Exchanges that never reached the model (no tokens) are not counted. */
fun summarizeUsage(rows: List<ExchangeUsage>, nowMillis: Long, zone: ZoneId, prices: Map<String, ModelPrice> = MODEL_PRICES): UsageSummary {
    val counted = rows.filter { it.inputTokens > 0 || it.outputTokens > 0 }
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val month = counted.filter { it.createdAtMillis >= monthStart }
    val inked = rows.mapNotNull { r -> r.firstInkMillis?.let { r.createdAtMillis to it } }
        .sortedBy { it.first }.takeLast(RECENT).map { it.second }
    return UsageSummary(
        total(month, prices), total(counted, prices),
        firstInkMedianMillis = inked.sorted().getOrNull(inked.size / 2),
        silentShare = if (month.isEmpty()) null else month.count { it.silent }.toDouble() / month.size,
    )
}

/** How many of the latest answers the first-ink median looks at. */
private const val RECENT = 50
