package com.nisaba.domain.usage

/** The token counts of one exchange, as the usage summary needs them. */
data class ExchangeUsage(val model: String, val createdAtMillis: Long, val inputTokens: Int, val cachedTokens: Int, val outputTokens: Int)

/** Dollars per million tokens. */
data class ModelPrice(val cacheHit: Double, val cacheMiss: Double, val output: Double)

data class UsageTotals(
    val answers: Int = 0,
    val inputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val outputTokens: Long = 0,
    /** Estimated dollars; null when some exchange used a model with no known price. */
    val costUsd: Double? = 0.0,
)

data class UsageSummary(val thisMonth: UsageTotals, val allTime: UsageTotals)
