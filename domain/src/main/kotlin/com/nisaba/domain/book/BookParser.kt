package com.nisaba.domain.book

private const val PAGES = """pages\s*=\s*"\s*(\d+)\s*(?:[-–—]\s*(\d+))?\s*""""
private val IDEA_RE = Regex("""<idea\s+$PAGES>(.*?)</idea>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
private val CONCEPT_RE = Regex("""<concept\s+$PAGES>(.*?)</concept>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
private val BOOK_RE = Regex("""<book>(.*?)</book>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
private val THESIS_RE = Regex("""<thesis>(.*?)</thesis>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
private val NEXT_RE = Regex("""<\s*next\s*/?\s*>(\s*<\s*/\s*next\s*>)?""", RegexOption.IGNORE_CASE)
private const val MAX_TITLE = 80
private const val MAX_SUMMARY = 300
private const val MAX_CONCEPTS = 16
const val MIN_CONCEPTS = 3

/** A tag's body "Name :: one sentence" (or just a name), its pages clamped to the book's [pageCount]. */
private fun parts(m: MatchResult, pageCount: Int): Triple<String, String, IntRange>? {
    val body = m.groupValues[3].trim().replace(Regex("""\s+"""), " ")
    val name = body.substringBefore("::").trim().trim('*', '"').take(MAX_TITLE)
    if (name.isEmpty()) return null
    val summary = if ("::" in body) body.substringAfter("::").trim().take(MAX_SUMMARY) else ""
    val a = m.groupValues[1].toInt().coerceIn(1, pageCount.coerceAtLeast(1))
    val b = (m.groupValues[2].toIntOrNull() ?: a).coerceIn(1, pageCount.coerceAtLeast(1))
    return Triple(name, summary, minOf(a, b)..maxOf(a, b))
}

/** The `<idea pages="12-15">Name :: sentence</idea>` lines of a part's reading. */
fun parseIdeas(reply: String, pageCount: Int): List<BookIdea> = IDEA_RE.findAll(reply).mapNotNull { m ->
    parts(m, pageCount)?.let { (name, summary, r) -> BookIdea(name, summary, r.first, r.last) }
}.toList()

/**
 * The map in a reply (`<book>`, `<thesis>` and `<concept>` lines); null when it holds too few concepts to walk through.
 * [title] (the file's) stands in when the reply names no book.
 */
fun parseBookMap(reply: String, title: String, pageCount: Int): BookMap? {
    val concepts = CONCEPT_RE.findAll(reply).mapNotNull { m ->
        parts(m, pageCount)?.let { (name, summary, r) -> Concept(name, summary, r.first, r.last) }
    }.distinctBy { it.title.lowercase() }.take(MAX_CONCEPTS).toList()
    if (concepts.size < MIN_CONCEPTS) return null
    val thesis = THESIS_RE.find(reply)?.groupValues?.get(1)?.trim()?.replace(Regex("""\s+"""), " ").orEmpty()
    val named = BOOK_RE.find(reply)?.groupValues?.get(1)?.trim()?.replace(Regex("""\s+"""), " ")?.take(MAX_TITLE * 2)
    return BookMap(named?.takeIf { it.isNotEmpty() } ?: title, thesis, concepts)
}

/** The reply asks for the next page of the walk (they wrote "next", in whatever words). */
fun hasNextTag(reply: String): Boolean = NEXT_RE.containsMatchIn(reply)

/** [line] without `<next/>` tags, so they are never handwritten. */
fun stripNextTags(line: String): String = line.replace(NEXT_RE, "")

/** A reply that is only `<next/>` at its start (before any answer): nothing to write. */
fun nextTagAtStart(text: String): MatchResult? = NEXT_RE.find(text)?.takeIf { it.range.first == 0 }
