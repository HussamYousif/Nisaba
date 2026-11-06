package com.nisaba.domain.conversation

import com.nisaba.domain.memory.Fact
import com.nisaba.domain.memory.formatMemoryContext
import com.nisaba.domain.persona.Persona
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.PageRect
import kotlin.math.ceil

const val TOKEN_BUDGET = 120_000
const val IMAGE_TOKENS = 1_024
private const val DOCUMENT_SHARE = 0.6
private const val MEMORY_SHARE = 0.25
private const val CHARS_PER_TOKEN = 3.5
private const val WINDOW_BLOCK = 20
private const val PAGE_TAG_TOKENS = 4

/** Pages either side of a book concept's own: the map's page numbers are a reader's estimate. */
private const val SPAN_PAD = 2

/**
 * Roughly the tokens [text] costs: about 3.5 characters each for alphabetic scripts, about one per character for
 * Chinese, Japanese and Korean (which the plain character count under-reads two to three times).
 */
fun estimateTokens(text: String): Int {
    val cjk = text.count(::isCjk)
    return ceil((text.length - cjk) / CHARS_PER_TOKEN + cjk).toInt()
}

/**
 * Document pages as `[p.N] text`. Whole document when it fits [maxTokens]; otherwise [span] (0-based: a book concept's
 * own pages, give or take [SPAN_PAD]) when it fits; otherwise the [WINDOW_BLOCK]-page block holding [currentPage]
 * (0-based) with the blocks either side, shrunk a page at a time until it fits. The window only moves when the reader
 * crosses into another block (or concept), so turning pages inside one keeps the cached prompt prefix (the document sits
 * in the system message). Returns null when there is no text at all (scanned PDFs: the image carries it).
 */
fun formatDocumentContext(
    pages: List<String>,
    currentPage: Int?,
    maxTokens: Int = (TOKEN_BUDGET * DOCUMENT_SHARE).toInt(),
    span: IntRange? = null,
): String? {
    if (pages.all { it.isBlank() }) return null
    fun render(range: IntRange) = range.joinToString("\n\n") { "[p.${it + 1}] ${pages[it].trim()}" }
    val all = render(pages.indices)
    if (estimateTokens(all) <= maxTokens) return all
    span?.let { (it.first - SPAN_PAD).coerceAtLeast(0)..(it.last + SPAN_PAD).coerceAtMost(pages.lastIndex) }
        ?.takeIf { !it.isEmpty() }?.let(::render)?.takeIf { estimateTokens(it) <= maxTokens }?.let { return it }
    val center = (currentPage ?: span?.let { (it.first + it.last) / 2 } ?: 0).coerceIn(0, pages.lastIndex)
    val block = center / WINDOW_BLOCK
    var first = ((block - 1) * WINDOW_BLOCK).coerceAtLeast(0)
    var last = ((block + 2) * WINDOW_BLOCK - 1).coerceAtMost(pages.lastIndex)
    val anchor = (block * WINDOW_BLOCK + WINDOW_BLOCK / 2).coerceAtMost(pages.lastIndex) // shrink around the block, not the page
    fun cost(i: Int) = estimateTokens(pages[i]) + PAGE_TAG_TOKENS
    var tokens = (first..last).sumOf(::cost)
    while (tokens > maxTokens && first < last) {
        val dropFirst = when {
            center == first -> false
            center == last -> true
            else -> anchor - first > last - anchor
        }
        if (dropFirst) { tokens -= cost(first); first++ } else { tokens -= cost(last); last-- }
    }
    val window = render(first..last)
    return if (estimateTokens(window) <= maxTokens) window else window.take((maxTokens * CHARS_PER_TOKEN).toInt()) // one giant page
}

private val CITATION = Regex("""\(p\.\s*(\d+)""")

/** The page the newest answer cited, 0-based (a lesson's place in its document: lesson pages are blank). */
fun lastCitedPage(exchanges: List<Exchange>): Int? = exchanges.sortedByDescending { it.createdAtMillis }.firstNotNullOfOrNull { e ->
    CITATION.findAll(e.answerMarkup).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.minus(1)?.takeIf { it >= 0 }
}

/**
 * Earlier exchanges as alternating user/assistant turns, oldest first. Silent exchanges (notes the user wrote without
 * asking) are folded into the next user turn; failed ones are skipped. Append-only by construction, so a new exchange
 * never changes earlier bytes (keeps the provider's prefix cache hitting).
 */
fun historyTurns(exchanges: List<Exchange>): Pair<List<Turn>, List<String>> {
    val turns = ArrayList<Turn>()
    val notes = ArrayList<String>()
    for (e in exchanges.sortedBy { it.createdAtMillis }) {
        when (e.status) {
            ExchangeStatus.Silent -> if (e.heard.isNotBlank()) notes += e.heard
            ExchangeStatus.Failed, ExchangeStatus.Cancelled, ExchangeStatus.Erased -> Unit
            ExchangeStatus.Complete, ExchangeStatus.Interrupted -> {
                if (e.answerMarkup.isBlank()) continue
                // an answer cut off mid-stream never got its <heard>: the question was not illegible, just not transcribed
                val said = e.heard.ifBlank { if (e.status == ExchangeStatus.Interrupted) "(the question on the page)" else "(illegible)" }
                val user = if (notes.isEmpty()) said else "(notes on the page: ${notes.joinToString("; ")})\n$said"
                notes.clear()
                turns += Turn.User(user); turns += Turn.Assistant(e.answerMarkup)
            }
        }
    }
    return turns to notes.toList()
}

private val CJK = listOf(0x3000..0x30FF, 0x3400..0x9FFF, 0xAC00..0xD7AF, 0xF900..0xFFEF)

private fun isCjk(c: Char) = CJK.any { c.code in it }

/** What a whole request costs going in, roughly (for requests that never reported their usage). */
fun estimateTokens(r: OracleRequest): Int =
    estimateTokens(r.system) + (r.documentContext?.let(::estimateTokens) ?: 0) + (r.memoryContext?.let(::estimateTokens) ?: 0) +
        r.history.sumOf(::tokens) + estimateTokens(r.instruction) + IMAGE_TOKENS * r.images.size

private fun tokens(t: Turn) = estimateTokens(if (t is Turn.User) t.text else (t as Turn.Assistant).text)

/**
 * Drops the oldest user/assistant pairs once history passes [budget] tokens, down to [TRIM_TO] of it: trimming a
 * little every request would change the prompt's start every time and miss the provider's cache each time.
 */
fun trimHistory(history: List<Turn>, budget: Int): List<Turn> {
    var total = history.sumOf(::tokens)
    if (total <= budget) return history
    var start = 0
    val target = (budget * TRIM_TO).toInt()
    while (total > target && start + 1 < history.size) { total -= tokens(history[start]) + tokens(history[start + 1]); start += 2 }
    return history.drop(start)
}

private const val TRIM_TO = 0.7

@Suppress("LongParameterList") // a request's whole context: one field per thing it draws on
class ContextInput(
    val documentPages: List<String>,
    val currentDocumentPage: Int?,
    val exchanges: List<Exchange>,
    val snapshot: PageSnapshot,
    val settings: Settings,
    val facts: List<Fact> = emptyList(),
    /** Non-null in a lesson notebook. */
    val teachGoal: String? = null,
    /** Why Nisaba writes this time. */
    val purpose: Purpose = Purpose.Writing,
    /** Lesson notebooks: what the tutor has recorded (see `formatLessonProgress`). */
    val lessonProgress: String? = null,
    /** The notebook's voice and answering habits. */
    val persona: Persona = PersonaRegistry.default,
    val style: com.nisaba.domain.notebook.AnswerStyle = com.nisaba.domain.notebook.AnswerStyle(),
    /** Their local date and time in words ("Thursday 15 October 2026, 21:40"), for dates and reminders; "" = left out. */
    val now: String = "",
    /** A private notebook (no memory is sent; no tags are asked for). */
    val isPrivate: Boolean = false,
    /** In a book notebook: its walk's map, and the concept the open page belongs to. */
    val book: com.nisaba.domain.book.BookPlace? = null,
) {
    val words: Int get() = (persona.words * style.length.factor).toInt()
}

/**
 * A reply's ceiling: room for a long answer when asked, but a runaway reply stops after a few pages instead of filling
 * them. A tidy copy can be a whole page of notes. Thinking may count its reasoning against the same budget: double it.
 */
fun maxTokensFor(purpose: Purpose, thinking: Boolean): Int {
    val base = when (purpose) {
        is Purpose.Tidy -> 2400
        Purpose.Redraw -> 2000
        is Purpose.Check, Purpose.Recap -> 1600
        is Purpose.Book -> 1800
        Purpose.Opening, Purpose.Resume, Purpose.Quiz, Purpose.Daily, is Purpose.Review -> 900
        Purpose.Writing, is Purpose.Again, Purpose.About -> 1200
    }
    return if (thinking) base * 2 else base
}

private const val FOCUS_MARGIN = 0.1f
private const val FOCUS_MIN = 100f
private const val FLAT_RATIO = 3f
private const val UNDERLINE_REACH = 1.2f

/**
 * The focus crop for new writing at [b]: the writing with a margin. A flat stroke (an underline, a bracket under a
 * line) also takes in the line above it: that is what it points at.
 */
fun focusArea(b: PageRect): PageRect {
    val margin = maxOf(b.bottom - b.top, FOCUS_MIN) * FOCUS_MARGIN
    val flat = b.bottom - b.top < FOCUS_MIN / 2 && b.right - b.left > (b.bottom - b.top) * FLAT_RATIO
    val area = PageRect(b.left - margin, b.top - margin, b.right + margin, b.bottom + margin)
    return if (flat) area.copy(top = area.top - FOCUS_MIN * UNDERLINE_REACH) else area
}

/** Numbers drawn just left of each line being checked, so marks can name lines ("1", "2", …). */
fun lineLabels(lines: List<PageRect>): List<SnapshotLabel> =
    lines.mapIndexed { i, r -> SnapshotLabel(com.nisaba.domain.shared.PagePoint(r.left, (r.top + r.bottom) / 2), "${i + 1}") }

/** On a printed page an answer is a note in the margin: this short unless they ask for more. */
const val GLOSS_WORDS = 35

/** The last message: what to do this time, where they are, notes they left, and the voice line. */
/** What this request is for, in one instruction. */
private fun purposeInstruction(input: ContextInput): String = when (val p = input.purpose) {
    Purpose.Opening -> INSTRUCTION_BEGIN_LESSON
    Purpose.Resume -> INSTRUCTION_RESUME_LESSON
    is Purpose.Tidy -> INSTRUCTION_TIDY
    is Purpose.Again -> INSTRUCTION_WITH_FOCUS + " " + againInstruction(p.previousAnswer, p.how)
    Purpose.Writing -> if (input.snapshot.focus != null) INSTRUCTION_WITH_FOCUS else INSTRUCTION_PAGE_ONLY
    is Purpose.Check -> INSTRUCTION_CHECK
    Purpose.Redraw -> INSTRUCTION_REDRAW
    Purpose.About -> INSTRUCTION_ABOUT
    Purpose.Quiz -> INSTRUCTION_QUIZ
    Purpose.Daily -> INSTRUCTION_DAILY
    Purpose.Recap -> INSTRUCTION_RECAP
    is Purpose.Review -> reviewInstruction(p.topics)
    is Purpose.Book -> input.book?.let { com.nisaba.domain.book.stepInstruction(it.map, p.step) } ?: INSTRUCTION_PAGE_ONLY
}

private fun instructionFor(input: ContextInput, trailingNotes: List<String>) = buildString {
    val p = input.purpose
    append(purposeInstruction(input))
    if (input.now.isNotEmpty()) append(" It is now ").append(input.now).append(".") // for dates and reminders
    if (input.isPrivate) append(" ").append(INSTRUCTION_PRIVATE)
    val onDocument = input.currentDocumentPage != null
    input.currentDocumentPage?.let { append(" They are on p. ${it + 1} of the document.") }
    if (trailingNotes.isNotEmpty()) append(" Notes they wrote earlier without asking: ${trailingNotes.joinToString("; ")}.")
    input.lessonProgress?.let { append(" ").append(it) }
    input.book?.takeIf { p !is Purpose.Book }?.let { append(com.nisaba.domain.book.walkPlace(it.map, it.concept, it.done)) }
    val glosses = onDocument && (p == Purpose.Writing || p == Purpose.About)
    if (glosses) append(" ").append(INSTRUCTION_GLOSS)
    when {
        p is Purpose.Tidy || p == Purpose.Redraw -> Unit // a copy: no voice
        p is Purpose.Check -> append(" ").append(checkReminder(input.persona))
        else -> {
            val words = if (glosses) minOf(input.words, GLOSS_WORDS) else input.words
            val tutors = input.teachGoal != null || input.book != null
            append(" ").append(voiceReminder(input.persona, tutors, words, transcribes = !p.textOnly))
        }
    }
}

/** The book concept a request is about: the one being walked, or the one the open page belongs to. */
private fun bookConcept(input: ContextInput): com.nisaba.domain.book.Concept? {
    val concept = (input.purpose as? Purpose.Book)?.step?.let { it as? com.nisaba.domain.book.BookStep.Concept }?.index
        ?: input.book?.concept.takeIf { input.purpose !is Purpose.Book }
    return concept?.let { input.book?.map?.concepts?.getOrNull(it) }
}

/** Where the document window centres: the page they are on, or the page last cited (a book concept has its own pages). */
private fun documentCentre(input: ContextInput): Int? = input.currentDocumentPage ?: lastCitedPage(input.exchanges)
    .takeIf { bookConcept(input) == null }

/** The map of the book and the whole picture work from the map alone: no pages of text (a book is long). */
private fun bookPageWithoutText(input: ContextInput): Boolean {
    val step = (input.purpose as? Purpose.Book)?.step ?: return false
    return step !is com.nisaba.domain.book.BookStep.Concept
}

fun buildRequest(input: ContextInput): OracleRequest {
    val span = bookConcept(input)?.let { com.nisaba.domain.book.conceptSpan(it) }
    val doc = formatDocumentContext(input.documentPages, documentCentre(input), span = span).takeUnless { bookPageWithoutText(input) }
    val memory = formatMemoryContext(input.facts, (TOKEN_BUDGET * MEMORY_SHARE).toInt())
    val (history, trailingNotes) = historyTurns(input.exchanges)
    val imageTokens = if (input.purpose.textOnly) 0 else IMAGE_TOKENS * (if (input.snapshot.focus != null) 2 else 1)
    // a tutor judges every answer they write, so "Questions only" would leave their answers unjudged
    val tutors = input.book != null || input.teachGoal != null
    val questionsOnly = !tutors && input.style.mode == com.nisaba.domain.notebook.AnswerMode.QuestionsOnly
    val voiced = systemPrompt(input.persona, questionsOnly, input.words)
    val system = when {
        input.book != null -> voiced + "\n\n" + com.nisaba.domain.book.bookPrompt(input.book.map)
        input.teachGoal != null -> voiced + "\n\n" + teachingPrompt(input.teachGoal)
        else -> voiced
    }
    val room = TOKEN_BUDGET - estimateTokens(system) - (doc?.let(::estimateTokens) ?: 0) -
        (memory?.let(::estimateTokens) ?: 0) - imageTokens
    val kept = trimHistory(history, room.coerceAtLeast(0))
    val instruction = instructionFor(input, trailingNotes)
    val p = input.purpose
    // copying, redrawing and checking are about getting it right, not about style
    val temperature = if (p is Purpose.Tidy || p is Purpose.Check || p == Purpose.Redraw) TIDY_TEMPERATURE else input.persona.temperature
    // asked again: the first answer wasn't good enough; checking work: worth thinking it through
    val thinking = input.settings.thinking || (p as? Purpose.Again)?.how == AgainHow.Afresh || p is Purpose.Check
    val images = if (p.textOnly) emptyList() else buildList {
        add(OracleImage(input.snapshot.page, ImageDetail.Auto))
        input.snapshot.focus?.let { add(OracleImage(it, ImageDetail.High)) }
    }
    return OracleRequest(
        system = system, documentContext = doc, history = kept, instruction = instruction, images = images,
        options = OracleOptions(
            input.settings.model, thinking, maxTokens = maxTokensFor(input.purpose, thinking),
            temperature = temperature,
        ),
        memoryContext = memory,
    )
}

/** [millis] in [zone] as the model reads a date: "Thursday 15 October 2026, 21:40". */
fun nowInWords(millis: Long, zone: java.time.ZoneId): String =
    java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", java.util.Locale.ENGLISH)
        .format(java.time.Instant.ofEpochMilli(millis).atZone(zone))
