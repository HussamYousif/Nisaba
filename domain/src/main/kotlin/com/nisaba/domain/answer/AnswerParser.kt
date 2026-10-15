package com.nisaba.domain.answer

import com.nisaba.domain.lesson.stripTopicTags
import com.nisaba.domain.memory.stripMemoryTags

private const val CLOSE_ANSWER = "</answer>"
/** `<silent/>` however the model spells it: `<silent />`, `<Silent>`, `<silent></silent>`. */
private val SILENT_RE = Regex("""^<\s*silent\s*/?\s*>(\s*<\s*/\s*silent\s*>)?""", RegexOption.IGNORE_CASE)
private val ANSWER_RE = Regex("""^<\s*answer\s*>""", RegexOption.IGNORE_CASE)

/** A line that is nothing but a stray tag (`</answer>`, `<thinking/>`): never ink. */
internal val LONE_TAG_RE = Regex("""^</?\s*[A-Za-z][\w-]*\s*/?>$""")
private const val OPEN_HEARD = "<heard>"
private const val CLOSE_HEARD = "</heard>"
private const val CLOSE_SILENT = "</silent>"

/**
 * Incremental parser for the model's reply (DESIGN §5.2). One instance per request.
 * Feed chunks as they stream in; blocks are reported as soon as they close, so the hand can start writing early.
 * Chunk boundaries never change the result.
 */
class AnswerParser {
    private enum class Phase { Start, InAnswer, AfterAnswer, Done }

    private val buf = StringBuilder()
    private var pos = 0
    private var phase = Phase.Start
    private var heardEmitted = false
    private val blocks = BlockSplitter()

    fun feed(chunk: String): List<ParseEvent> {
        buf.append(chunk)
        return run(final = false)
    }

    /** Call once when the stream ends; flushes everything still pending. */
    fun finish(): List<ParseEvent> = run(final = true)

    private fun run(final: Boolean): List<ParseEvent> {
        val out = ArrayList<ParseEvent>()
        var progressed = true
        while (progressed && phase != Phase.Done) {
            progressed = when (phase) {
                Phase.Start -> start(out, final)
                Phase.InAnswer -> inAnswer(out, final)
                Phase.AfterAnswer -> afterAnswer(out, final)
                Phase.Done -> false
            }
        }
        if (final && !heardEmitted) { heardEmitted = true; out += ParseEvent.Heard("") }
        return out
    }

    private fun rest() = buf.substring(pos)

    private fun start(out: MutableList<ParseEvent>, final: Boolean): Boolean {
        val r = rest().trimStart()
        val skipped = rest().length - r.length
        val silent = SILENT_RE.find(r)
        val answer = ANSWER_RE.find(r)
        when {
            r.isEmpty() -> { phase = if (final) Phase.Done else phase; return false }
            // `<silent>` may still grow a `</silent>`: wait until something follows it
            silent != null && (final || silentIsWhole(silent, r)) -> {
                pos += skipped + silent.value.length; out += ParseEvent.Silent; phase = Phase.AfterAnswer
            }
            answer != null -> { pos += skipped + answer.value.length; phase = Phase.InAnswer }
            r.startsWith(OPEN_HEARD) -> return heardFirst(r, skipped, out, final)              // <heard> before <answer>
            !final && r.startsWith("<") && (!r.contains('>') || silent != null) -> return false // tag still arriving
            else -> { pos += skipped; phase = Phase.InAnswer }                                  // model forgot <answer>
        }
        return true
    }

    private fun silentIsWhole(m: MatchResult, r: String): Boolean {
        if (m.value.endsWith("/>") || m.groupValues[1].isNotEmpty()) return true
        val after = r.substring(m.value.length).trimStart()
        return after.isNotEmpty() && !CLOSE_SILENT.startsWith(after.take(CLOSE_SILENT.length), ignoreCase = true)
    }

    /** The model put `<heard>` first: report it and keep looking for the answer. */
    private fun heardFirst(r: String, skipped: Int, out: MutableList<ParseEvent>, final: Boolean): Boolean {
        val close = r.indexOf(CLOSE_HEARD)
        if (close < 0) {
            if (final) {
                out += ParseEvent.Heard(r.removePrefix(OPEN_HEARD).trim()); heardEmitted = true; pos = buf.length; phase = Phase.Done
            }
            return false
        }
        out += ParseEvent.Heard(r.substring(OPEN_HEARD.length, close).trim()); heardEmitted = true
        pos += skipped + close + CLOSE_HEARD.length
        return true
    }

    private fun inAnswer(out: MutableList<ParseEvent>, final: Boolean): Boolean {
        val text = rest()
        val term = listOf(CLOSE_ANSWER, OPEN_HEARD).map { it to text.indexOf(it) }.filter { it.second >= 0 }.minByOrNull { it.second }
        if (term != null) {
            emitLines(text.substring(0, term.second), flushTail = true, out)
            pos += term.second + (if (term.first == CLOSE_ANSWER) CLOSE_ANSWER.length else 0)
            out += blocks.flush()
            phase = Phase.AfterAnswer
            return true
        }
        val hold = heldBackSuffix(text)
        val safe = text.length - hold
        val lastNl = text.lastIndexOf('\n', safe - 1)
        if (lastNl >= 0) {
            emitLines(text.substring(0, lastNl + 1), flushTail = false, out)
            pos += lastNl + 1
        }
        if (final) {
            emitLines(rest(), flushTail = true, out); pos = buf.length
            out += blocks.flush()
            phase = Phase.Done
        }
        return false
    }

    /** Length of a trailing fragment that might be the start of a terminator tag. */
    private fun heldBackSuffix(text: String): Int {
        var best = 0
        for (tag in listOf(CLOSE_ANSWER, OPEN_HEARD)) {
            for (k in minOf(tag.length - 1, text.length) downTo 1) {
                if (text.endsWith(tag.substring(0, k))) { best = maxOf(best, k); break }
            }
        }
        return best
    }

    private fun emitLines(chunk: String, flushTail: Boolean, out: MutableList<ParseEvent>) {
        if (chunk.isEmpty()) return
        val lines = chunk.split('\n')
        val complete = if (flushTail) lines else lines.dropLast(1)
        for ((i, raw) in complete.withIndex()) {
            val l = com.nisaba.domain.conversation.stripTaskTags(stripTopicTags(stripMemoryTags(raw)))
            val trailingEmpty = flushTail && i == complete.lastIndex && raw.isEmpty()
            val tagOnly = l.isBlank() && raw.isNotBlank() // a memory tag on its own line: not ink, not a paragraph break
            if (!trailingEmpty && !tagOnly) out += blocks.line(l.trimEnd('\r'))
        }
    }

    private fun afterAnswer(out: MutableList<ParseEvent>, final: Boolean): Boolean {
        val text = rest()
        val open = text.indexOf(OPEN_HEARD)
        if (heardEmitted) { phase = Phase.Done; return false } // it came first
        if (open < 0) { phase = if (final) Phase.Done else phase; return false }
        val from = open + OPEN_HEARD.length
        val close = text.indexOf(CLOSE_HEARD, from)
        if (close < 0) {
            if (final) { out += ParseEvent.Heard(text.substring(from).trim()); heardEmitted = true; pos = buf.length; phase = Phase.Done }
            return false
        }
        out += ParseEvent.Heard(text.substring(from, close).trim()); heardEmitted = true
        pos += close + CLOSE_HEARD.length
        phase = Phase.Done
        return true
    }
}
