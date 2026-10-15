package com.nisaba.domain.usage

/** The token counts of one exchange, as the usage summary needs them. */
data class ExchangeUsage(
    val model: String,
    val createdAtMillis: Long,
    val inputTokens: Int,
    val cachedTokens: Int,
    val outputTokens: Int,
    /** The pause got no answer (the page held nothing to answer). */
    val silent: Boolean = false,
    /** Milliseconds from sending to the first block the hand could write. */
    val firstInkMillis: Long? = null,
)

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

/**
 * [firstInkMedianMillis] = the median time from the end of the pause to the first ink, over the last answers that
 * recorded it; [silentShare] = the share of this month's pauses that got no answer (null: nothing yet).
 */
data class UsageSummary(
    val thisMonth: UsageTotals,
    val allTime: UsageTotals,
    val firstInkMedianMillis: Long? = null,
    val silentShare: Double? = null,
)
