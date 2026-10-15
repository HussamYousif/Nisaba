package com.nisaba.domain.memory

import com.nisaba.domain.conversation.Oracle
import com.nisaba.domain.conversation.OracleEvent
import com.nisaba.domain.conversation.OracleOptions
import com.nisaba.domain.conversation.OracleRequest
import com.nisaba.domain.shared.Clock

/**
 * Keeps long-term memory small enough to send whole: once there are [threshold] facts, every [every] new ones, the
 * model merges duplicates and drops trivia (one cheap text-only request). Never drops a fact for being old; a reply
 * that would forget more than half of memory is taken for a misunderstanding and ignored.
 */
class ConsolidateMemoryUseCase(
    private val store: MemoryStore,
    private val oracle: Oracle,
    private val clock: Clock,
    private val threshold: Int = 150,
    private val every: Int = 25,
) {
    suspend operator fun invoke(model: String): MemoryPlan? {
        val facts = store.all()
        if (facts.size < threshold || facts.size % every != 0) return null
        val request = OracleRequest(
            system = CLEANUP_PROMPT, documentContext = null, history = emptyList(),
            instruction = facts.joinToString("\n") { "#${it.id.value} [${it.category.key}] ${it.text}" }, images = emptyList(),
            options = OracleOptions(model, thinking = false, maxTokens = MAX_TOKENS, temperature = TEMPERATURE),
        )
        val reply = StringBuilder()
        oracle.ask(request).collect { if (it is OracleEvent.Delta) reply.append(it.text) }
        val plan = planMemory(parseMemoryOps(reply.toString()), facts, maxAdds = facts.size / 2)
        if (plan.isEmpty || plan.forget.size > facts.size / 2) return null
        store.apply(plan.add.map { NewFact(it.category, it.text, null, clock.nowMillis()) }, plan.forget)
        return plan
    }

    private companion object {
        const val MAX_TOKENS = 2000
        const val TEMPERATURE = 0.2
        val CLEANUP_PROMPT = """
You tidy a person's long-term memory, kept by their handwritten notebook. The message lists every fact, one per line as "#id [category] fact".
Merge duplicates and near-duplicates into one fact. Drop trivia, passing moods and anything that is not about the person or their life. Where two facts contradict each other, keep the newer one (the higher id). Never drop a lasting fact just because it is old.
Reply only with tags: <forget id="12"/> for every fact to drop or replace, and <remember category="…">the merged fact</remember> for every replacement (one short sentence, at most 20 words, without "the user"). Categories: me, people, projects, books, screen, music, preferences, goals, other.
If nothing needs tidying, reply with nothing at all.
        """.trimIndent()
    }
}
