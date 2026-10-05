package com.nisaba.domain.memory

import com.nisaba.domain.shared.ExchangeId

/** Short numeric id: the model sees it as `#12` and names it in `<forget id="12"/>`, so it must stay cheap in tokens. */
@JvmInline value class FactId(val value: Long)

/** Fixed shelves for what Nisaba knows; [key] is what the model writes in `category="…"`. */
enum class FactCategory(val key: String) {
    Me("me"), People("people"), Projects("projects"), Books("books"), Screen("screen"), Music("music"),
    Preferences("preferences"), Goals("goals"), Other("other");

    companion object {
        fun of(key: String?): FactCategory = entries.firstOrNull { it.key == key?.trim()?.lowercase() } ?: Other
    }
}

/** One durable thing Nisaba knows about the person, shared by every notebook (DESIGN §4.5). */
data class Fact(
    val id: FactId,
    val category: FactCategory,
    val text: String,
    val sourceExchangeId: ExchangeId?,
    val createdAtMillis: Long,
)

data class NewFact(val category: FactCategory, val text: String, val sourceExchangeId: ExchangeId?, val createdAtMillis: Long)

/** What the model asked for in its reply: `<remember category="…">…</remember>` or `<forget id="N"/>`. */
sealed interface MemoryOp {
    data class Remember(val category: FactCategory, val text: String) : MemoryOp
    data class Forget(val id: FactId) : MemoryOp
}

/** The checked outcome of a reply's ops: what to add and which existing facts to drop. */
data class MemoryPlan(val add: List<MemoryOp.Remember>, val forget: List<FactId>) {
    val isEmpty: Boolean get() = add.isEmpty() && forget.isEmpty()
}
