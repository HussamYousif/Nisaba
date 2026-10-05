package com.nisaba.domain.memory

private val REMEMBER_RE = Regex("""<remember(?:\s+category\s*=\s*"([^"]*)")?\s*>(.*?)</remember>""", RegexOption.DOT_MATCHES_ALL)
private val FORGET_RE = Regex("""<forget\s+id\s*=\s*"?[^\d"]*(\d+)"?\s*/?>""")

/** Every memory tag in a raw reply, in the order written. Malformed tags are ignored. */
fun parseMemoryOps(reply: String): List<MemoryOp> {
    val remembers = REMEMBER_RE.findAll(reply).map { m ->
        m.range.first to MemoryOp.Remember(FactCategory.of(m.groupValues[1]), m.groupValues[2].trim())
    }
    val forgets = FORGET_RE.findAll(reply).mapNotNull { m ->
        m.groupValues[1].toLongOrNull()?.let { m.range.first to MemoryOp.Forget(FactId(it)) }
    }
    return (remembers + forgets).sortedBy { it.first }.map { it.second }.toList()
}

/** [line] without memory tags, so a tag the model put inside `<answer>` is never handwritten. */
fun stripMemoryTags(line: String): String = line.replace(REMEMBER_RE, "").replace(FORGET_RE, "")
