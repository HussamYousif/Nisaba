package com.nisaba.domain.conversation

import com.nisaba.domain.memory.Fact
import com.nisaba.domain.memory.formatMemoryContext
import com.nisaba.domain.persona.Persona
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.settings.Settings
import kotlin.math.ceil

const val TOKEN_BUDGET = 120_000
const val IMAGE_TOKENS = 1_024
private const val DOCUMENT_SHARE = 0.6
private const val MEMORY_SHARE = 0.25
private const val CHARS_PER_TOKEN = 3.5
private const val WINDOW_PAGES = 30

fun estimateTokens(text: String): Int = ceil(text.length / CHARS_PER_TOKEN).toInt()

/**
 * Document pages as `[p.N] text`. Whole document when it fits [maxTokens]; otherwise a window of ±[WINDOW_PAGES] pages
 * around [currentPage] (0-based). Returns null when there is no text at all (scanned PDFs: the page image carries it).
 */
fun formatDocumentContext(pages: List<String>, currentPage: Int?, maxTokens: Int = (TOKEN_BUDGET * DOCUMENT_SHARE).toInt()): String? {
    if (pages.all { it.isBlank() }) return null
    fun render(range: IntRange) = range.joinToString("\n\n") { "[p.${it + 1}] ${pages[it].trim()}" }
    val all = render(pages.indices)
    if (estimateTokens(all) <= maxTokens) return all
    val center = (currentPage ?: 0).coerceIn(0, pages.lastIndex)
    val window = render((center - WINDOW_PAGES).coerceAtLeast(0)..(center + WINDOW_PAGES).coerceAtMost(pages.lastIndex))
    return if (estimateTokens(window) <= maxTokens) window else window.take((maxTokens * CHARS_PER_TOKEN).toInt())
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
            ExchangeStatus.Failed -> Unit
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

private fun tokens(t: Turn) = estimateTokens(if (t is Turn.User) t.text else (t as Turn.Assistant).text)

/** Drops the oldest user/assistant pairs until history fits [budget] tokens. */
fun trimHistory(history: List<Turn>, budget: Int): List<Turn> {
    var start = 0
    var total = history.sumOf(::tokens)
    while (total > budget && start + 1 < history.size) { total -= tokens(history[start]) + tokens(history[start + 1]); start += 2 }
    return history.drop(start)
}

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
    /** The notebook's voice. */
    val persona: Persona = PersonaRegistry.default,
)

/**
 * A reply's ceiling: room for a long answer when asked, but a runaway reply stops after a few pages instead of filling
 * them. A tidy copy can be a whole page of notes. Thinking may count its reasoning against the same budget: double it.
 */
fun maxTokensFor(purpose: Purpose, thinking: Boolean): Int {
    val base = when (purpose) {
        is Purpose.Tidy -> 2400
        Purpose.Opening, Purpose.Resume -> 900
        Purpose.Writing, is Purpose.Again -> 1200
    }
    return if (thinking) base * 2 else base
}

fun buildRequest(input: ContextInput): OracleRequest {
    val doc = formatDocumentContext(input.documentPages, input.currentDocumentPage)
    val memory = formatMemoryContext(input.facts, (TOKEN_BUDGET * MEMORY_SHARE).toInt())
    val (history, trailingNotes) = historyTurns(input.exchanges)
    val imageTokens = IMAGE_TOKENS * (if (input.snapshot.focus != null) 2 else 1)
    val voiced = systemPrompt(input.persona)
    val system = input.teachGoal?.let { voiced + "\n\n" + teachingPrompt(it) } ?: voiced
    val room = TOKEN_BUDGET - estimateTokens(system) - (doc?.let(::estimateTokens) ?: 0) -
        (memory?.let(::estimateTokens) ?: 0) - imageTokens
    val kept = trimHistory(history, room.coerceAtLeast(0))
    val instruction = buildString {
        append(
            when (val p = input.purpose) {
                Purpose.Opening -> INSTRUCTION_BEGIN_LESSON
                Purpose.Resume -> INSTRUCTION_RESUME_LESSON
                is Purpose.Tidy -> INSTRUCTION_TIDY
                is Purpose.Again -> INSTRUCTION_WITH_FOCUS + " " + againInstruction(p.previousAnswer)
                Purpose.Writing -> if (input.snapshot.focus != null) INSTRUCTION_WITH_FOCUS else INSTRUCTION_PAGE_ONLY
            },
        )
        input.currentDocumentPage?.let { append(" They are on p. ${it + 1} of the document.") }
        if (trailingNotes.isNotEmpty()) append(" Notes they wrote earlier without asking: ${trailingNotes.joinToString("; ")}.")
        input.lessonProgress?.let { append(" ").append(it) }
        if (input.purpose !is Purpose.Tidy) append(" ").append(voiceReminder(input.persona, lesson = input.teachGoal != null))
    }
    val temperature = if (input.purpose is Purpose.Tidy) TIDY_TEMPERATURE else input.persona.temperature
    val images = buildList {
        add(OracleImage(input.snapshot.page, ImageDetail.Auto))
        input.snapshot.focus?.let { add(OracleImage(it, ImageDetail.High)) }
    }
    return OracleRequest(
        system = system, documentContext = doc, history = kept, instruction = instruction, images = images,
        options = OracleOptions(
            input.settings.model, input.settings.thinking, maxTokens = maxTokensFor(input.purpose, input.settings.thinking),
            temperature = temperature,
        ),
        memoryContext = memory,
    )
}
