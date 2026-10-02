package com.nisaba.domain.notebook

import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId

const val MAX_NOTEBOOK_NAME = 60
const val PAGE_POSITION_STEP = 1024.0
private const val MIN_POSITION_GAP = 1e-6

/** Trims and bounds a name; blank names become "Untitled", "Untitled 2", … avoiding [existing]. */
fun validateNotebookName(raw: String?, existing: Collection<String>): String {
    val trimmed = raw.orEmpty().trim().take(MAX_NOTEBOOK_NAME).trim()
    if (trimmed.isNotEmpty()) return trimmed
    val taken = existing.map { it.lowercase() }.toSet()
    if ("untitled" !in taken) return "Untitled"
    var n = 2
    while ("untitled $n" in taken) n++
    return "Untitled $n"
}

/** Stable per-notebook seed for generated covers. */
fun coverSeed(name: String, id: NotebookId): Int = (name.hashCode() * 31) xor id.value.hashCode()

fun positionBetween(prev: Double?, next: Double?): Double = when {
    prev == null && next == null -> PAGE_POSITION_STEP
    prev == null -> next!! - PAGE_POSITION_STEP
    next == null -> prev + PAGE_POSITION_STEP
    else -> (prev + next) / 2.0
}

fun gapTooSmall(prev: Double, next: Double): Boolean = next - prev < MIN_POSITION_GAP

/** Fresh evenly spaced positions for [ids] (in their current order). */
fun rebalancedPositions(ids: List<PageId>): List<Pair<PageId, Double>> =
    ids.mapIndexed { i, id -> id to (i + 1) * PAGE_POSITION_STEP }
