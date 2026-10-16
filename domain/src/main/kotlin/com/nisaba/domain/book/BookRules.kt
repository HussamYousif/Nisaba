package com.nisaba.domain.book

import com.nisaba.domain.shared.PageId

// Reading a book into a map of its core concepts, then walking through them a page at a time.

/** A book this short is read in one request; a longer one part by part (each part about [PART_CHARS] of text). */
private const val WHOLE_BOOK_CHARS = 160_000
private const val PART_CHARS = 60_000

/** Less text than this is no book to walk through (a scan, say: its pages are pictures). */
const val MIN_BOOK_CHARS = 2_000

/** The book's pages (0-based ranges) as the parts it is read in, in order. Blank pages ride along with their neighbours. */
fun bookParts(pages: List<String>): List<IntRange> {
    if (pages.isEmpty()) return emptyList()
    if (pages.sumOf { it.length } <= WHOLE_BOOK_CHARS) return listOf(pages.indices)
    val parts = ArrayList<IntRange>()
    var start = 0; var size = 0
    for ((i, p) in pages.withIndex()) {
        if (size > 0 && size + p.length > PART_CHARS) { parts += start until i; start = i; size = 0 }
        size += p.length
    }
    parts += start..pages.lastIndex
    return parts
}

/** Pages [range] (0-based) as the model reads them: `[p.12] text`, as everywhere else. */
fun formatPages(pages: List<String>, range: IntRange): String =
    range.filter { pages[it].isNotBlank() }.joinToString("\n\n") { "[p.${it + 1}] ${pages[it].trim()}" }

const val READ_SYSTEM = "You read one part of a book for someone who wants to understand the whole book. You note the core " +
    "ideas this part develops, like a careful reader's margin notes: concepts, arguments, mechanisms, distinctions. Never " +
    "anecdotes or examples on their own, exercises, front matter, acknowledgements, the index or the bibliography."

fun readInstruction(title: String, range: IntRange): String =
    "Pages ${range.first + 1}–${range.last + 1} of \"$title\" are in the document. List the core ideas they develop, in " +
        "order, at most 6, one per line, exactly like this:\n<idea pages=\"12-15\">Short name :: one sentence on what it says</idea>\n" +
        "Use the page numbers shown as [p.N]. Write in the book's language. Nothing else."

const val MAP_SYSTEM = "You plan how to walk a reader through a book so they come away with its overall conceptual " +
    "understanding: its central argument or purpose, and the core concepts it is built from, in the order that makes them " +
    "easiest to learn (usually the book's own order)."

private const val MAP_FORMAT = "Make the map. First <book>the book's title — its author</book>, then <thesis>the book's " +
    "central argument or purpose in two or three sentences</thesis>, then its core concepts in the order to learn them, one " +
    "per line:\n<concept pages=\"12-30\">Short name :: one sentence on what it is and why it matters to the book</concept>\n" +
    "Between 5 and 12 concepts: the few that carry the book's argument (a short essay may need only 5; even a long technical " +
    "book no more than 14). Merge ideas that are really one concept and leave out minor ones; the pages must span where each " +
    "concept is developed. Write in the book's language. Nothing else."

/** The map from the whole text (a short book: it is in the document). */
fun mapInstructionWhole(title: String, pageCount: Int): String =
    "The whole text of \"$title\" ($pageCount pages) is in the document. $MAP_FORMAT"

/** The map from the ideas each part was found to hold (a long book). */
fun mapInstructionFromIdeas(title: String, pageCount: Int, ideas: List<BookIdea>): String = buildString {
    append("These are the ideas found in each part of \"").append(title).append("\" (").append(pageCount).append(" pages):\n")
    for (i in ideas) {
        append("p.").append(i.firstPage).append(if (i.lastPage != i.firstPage) "–${i.lastPage}" else "").append(' ')
        append(i.title).append(if (i.summary.isNotBlank()) ": ${i.summary}" else "").append('\n')
    }
    append(MAP_FORMAT)
}

/**
 * Appended to the system prompt in a book notebook: what the walk is, the book's argument and its map. Stable for the
 * whole walk (where they are goes in the instruction), so the cached prefix keeps hitting.
 */
fun bookPrompt(map: BookMap): String = buildString {
    append("# Walking through a book\n")
    append("This notebook walks the person through the book \"").append(map.title)
    append("\" (its text is in the document), one core concept at a time, so they come away with its overall conceptual ")
    append("understanding. Stay in your own voice: you are their guide through this book.\n")
    if (map.thesis.isNotBlank()) append("The book's argument: ").append(map.thesis).append('\n')
    append("The map:\n")
    map.concepts.forEachIndexed { i, c -> append(i + 1).append(". ").append(c.title).append(" (").append(c.pages).append(")\n") }
    append(
        """
- Each concept gets its own fresh page, written when the instruction says so. Explain it in your own words for real understanding, not as a summary of the pages: what it is, why it matters to the book's argument, an example or an analogy, and how it builds on the concepts before it. Cite pages (p. 14).
- On the page they may write questions or thoughts: answer them in the light of the book and the concept at hand, briefly.
- When they ask to move on ("next", "neste", "go on", "continue", "weiter" or the like, in any language), reply exactly <next/> and then <heard>…</heard>, nothing else.
""".trim(),
    )
}

/** The instruction for one page of the walk. */
fun stepInstruction(map: BookMap, step: BookStep): String = when (step) {
    BookStep.Overview -> "This is the first page of the walk, still blank, and the whole map must fit on it. Never reply " +
        "<silent/>. Greet them in one line, in your voice. Then a heading with the book's title, the book's argument in two " +
        "sentences, and the map as a numbered list of the concepts' names only (no pages, no descriptions). End with one " +
        "line: when they are ready, they write \"next\"; or they ask anything."
    is BookStep.Concept -> conceptInstruction(map, step.index)
    BookStep.Synthesis -> "They have walked through all ${map.concepts.size} concepts. On this fresh page, never reply " +
        "<silent/>: the heading \"The whole picture\", then how the concepts fit together into the book's argument (a small " +
        "diagram if it helps), the three things most worth keeping, and one question to carry on with."
}

private fun conceptInstruction(map: BookMap, i: Int): String {
    val c = map.concepts[i]
    val n = map.concepts.size
    val before = map.concepts.take(i).takeLast(3).joinToString(", ") { "\"${it.title}\"" }
    val then = map.concepts.getOrNull(i + 1)?.let { "\"${it.title}\"" } ?: "the whole picture"
    return buildString {
        append("Walk them through concept ${i + 1} of $n: \"${c.title}\" (${c.pages}), on this fresh page. Never reply <silent/>. ")
        append("Start with the heading \"${i + 1}. ${c.title}\". Explain it as the walk asks, in about 200 words, with a small ")
        append("table, formula, diagram or code block only if it truly helps.")
        if (before.isNotEmpty()) append(" Connect it to what came before: $before.")
        append(" End with one question worth thinking about, then one line: they write \"next\" for $then.")
    }
}

/** Where they are, for what they write on a page of the walk. */
fun walkPlace(map: BookMap, concept: Int?): String = when (val c = concept?.let { map.concepts.getOrNull(it) }) {
    null -> " They are on the map of the book."
    else -> " They are on concept ${concept + 1} of ${map.concepts.size} of the walk: \"${c.title}\" (${c.pages})."
}

/** What the person "said", as history shows a page of the walk. */
fun bookHeard(map: BookMap?, step: BookStep): String = when (step) {
    BookStep.Overview -> "(asks for the map of the book)"
    is BookStep.Concept -> "(asks to be walked through: ${map?.concepts?.getOrNull(step.index)?.title ?: "the next concept"})"
    BookStep.Synthesis -> "(asks for the whole picture)"
}

/** The 0-based page the document window centres on for [concept] (the middle of its pages). */
fun centrePage(concept: Concept): Int = ((concept.firstPage + concept.lastPage) / 2 - 1).coerceAtLeast(0)

/** The map once [step] was written, starting on [pageId]. */
fun BookMap.walkedOn(step: BookStep, pageId: PageId): BookMap = when (step) {
    BookStep.Overview -> copy(overviewPageId = overviewPageId ?: pageId)
    is BookStep.Concept -> copy(
        concepts = concepts.mapIndexed { i, c -> if (i == step.index && c.pageId == null) c.copy(pageId = pageId) else c },
    )
    BookStep.Synthesis -> copy(synthesisPageId = synthesisPageId ?: pageId)
}

/**
 * The concept a page of the notebook belongs to: the last one walked on it or before it ([order] = the notebook's pages
 * in order). Null on the map's page, or before the walk began.
 */
fun conceptAt(map: BookMap, order: List<PageId>, page: PageId): Int? {
    val at = order.indexOf(page)
    if (at < 0) return null
    return map.concepts.withIndex()
        .mapNotNull { (i, c) -> c.pageId?.let(order::indexOf)?.takeIf { it in 0..at }?.let { i to it } }
        .maxByOrNull { it.second }?.first
}
