package com.nisaba.domain.memory

import com.nisaba.domain.shared.Clock
import com.nisaba.domain.shared.ExchangeId

/** Applies the memory tags of one saved reply. Returns what changed, or null when nothing did. */
class ApplyMemoryUseCase(private val store: MemoryStore, private val clock: Clock) {
    suspend operator fun invoke(reply: String, source: ExchangeId): MemoryPlan? {
        val ops = parseMemoryOps(reply)
        if (ops.isEmpty()) return null
        val plan = planMemory(ops, store.all())
        if (plan.isEmpty) return null
        val now = clock.nowMillis()
        store.apply(plan.add.map { NewFact(it.category, it.text, source, now) }, plan.forget)
        return plan
    }
}
