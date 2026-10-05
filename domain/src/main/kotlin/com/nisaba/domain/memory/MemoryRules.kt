package com.nisaba.domain.memory

const val MAX_FACT_CHARS = 200
const val MAX_ADDS_PER_REPLY = 5
private const val CHARS_PER_TOKEN = 3.5
private const val NOTE_CHARS = 60

private fun normalized(text: String) = text.lowercase().replace(Regex("""\s+"""), " ").trim().trimEnd('.', '!')

private fun cleaned(text: String) = text.replace(Regex("""\s+"""), " ").trim().take(MAX_FACT_CHARS)

/**
 * Checks a reply's ops against what is already known: drops blanks, duplicates (of kept facts or within the reply),
 * forgets of unknown ids, and anything past [MAX_ADDS_PER_REPLY]. A forget followed by a remember is how a fact changes.
 */
fun planMemory(ops: List<MemoryOp>, existing: List<Fact>): MemoryPlan {
    val known = existing.associateBy { it.id }
    val forget = ops.filterIsInstance<MemoryOp.Forget>().map { it.id }.filter { it in known }.distinct()
    val seen = existing.filter { it.id !in forget }.mapTo(HashSet()) { normalized(it.text) }
    val add = ops.filterIsInstance<MemoryOp.Remember>()
        .map { it.copy(text = cleaned(it.text)) }
        .filter { it.text.isNotEmpty() && seen.add(normalized(it.text)) }
        .take(MAX_ADDS_PER_REPLY)
    return MemoryPlan(add, forget)
}

/**
 * Facts as `## category` sections of `#id text` lines, categories in a fixed order and facts oldest first, so a new
 * fact only changes bytes from its own section on. Over [maxTokens], the newest facts that fit are kept.
 * Null when nothing is known yet.
 */
fun formatMemoryContext(facts: List<Fact>, maxTokens: Int): String? {
    if (facts.isEmpty()) return null
    val budget = (maxTokens * CHARS_PER_TOKEN).toInt()
    val kept = ArrayList<Fact>()
    var used = 0
    for (f in facts.sortedByDescending { it.id.value }) {
        used += line(f).length + 1
        if (used > budget) break
        kept += f
    }
    if (kept.isEmpty()) return null
    return kept.groupBy { it.category }.toSortedMap().entries.joinToString("\n\n") { (cat, list) ->
        "## ${cat.key}\n" + list.sortedBy { it.id.value }.joinToString("\n", transform = ::line)
    }
}

private fun line(f: Fact) = "#${f.id.value} ${f.text}"

/** The toolbar's one line after a reply changed the memory, e.g. "Remembered: loved Dune". */
fun memoryNote(plan: MemoryPlan): String? = when {
    plan.add.isNotEmpty() -> ("Remembered: " + plan.add.joinToString("; ") { it.text }).let {
        if (it.length > NOTE_CHARS) it.take(NOTE_CHARS - 1).trimEnd() + "…" else it
    }
    plan.forget.isNotEmpty() -> if (plan.forget.size == 1) "Forgot one thing" else "Forgot ${plan.forget.size} things"
    else -> null
}
