package com.nisaba.tools

import com.nisaba.domain.answer.AnswerParser
import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.answer.ParseEvent
import com.nisaba.domain.memory.parseMemoryOps
import com.nisaba.domain.memory.MemoryOp
import com.nisaba.domain.persona.Persona

private val NORWEGIAN = Regex(
    """\b(er|og|det|den|et|en|som|til|på|så|ikke|eller|du|jeg|men|med|for|av|bare|svar|gang|ganger)\b""", RegexOption.IGNORE_CASE,
)
private val MATH = Regex("""\$[^$]*\$""")

/** Common words of languages the cases are not written in: three or more in an English case = the wrong language. */
private val FOREIGN = Regex(
    """\b(il|che|per|della|sono|una|las|los|que|por|des|les|est|und|ist|nicht|og|det|ikke)\b""", RegexOption.IGNORE_CASE,
)

/** The words the hand would write (inline math counts as one word). */
fun answerWords(blocks: List<Block>): String = blocks.joinToString(" ") { b ->
    when (b) {
        is Block.Paragraph -> inlineText(b.inlines)
        is Block.Heading -> inlineText(b.inlines)
        is Block.ListItem -> inlineText(b.inlines)
        is Block.Table -> (b.header + b.rows.flatten()).joinToString(" ") { inlineText(it) }
        else -> "x"
    }
}

private fun inlineText(inlines: List<Inline>) = inlines.joinToString("") { if (it is Inline.Text) it.s else " x " }

/** Scores one reply against its case with the app's own parser: what problems a person would notice. */
fun scoreReply(reply: String, case: EvalCase, persona: Persona, oldVoice: String?): Pair<List<String>, Int> {
    val parser = AnswerParser()
    val events = parser.feed(reply) + parser.finish()
    val silent = events.any { it is ParseEvent.Silent }
    val blocks = events.filterIsInstance<ParseEvent.BlockReady>().map { it.block }
    val heard = events.filterIsInstance<ParseEvent.Heard>().lastOrNull()?.text.orEmpty()
    val text = answerWords(blocks)
    val words = text.split(Regex("""\s+""")).count { it.isNotBlank() }
    val problems = contractProblems(silent, case, heard, blocks, words, persona) + contentProblems(text, reply, case, oldVoice)
    return problems to words
}

/** The reply format: silence when expected, a transcription, blocks, the length. */
private fun contractProblems(
    silent: Boolean,
    case: EvalCase,
    heard: String,
    blocks: List<Block>,
    words: Int,
    persona: Persona,
) = buildList {
    if (silent != case.silent) add(if (silent) "silent (should answer)" else "answered (should be silent)")
    if (heard.isBlank()) add("no <heard>")
    if (!silent && blocks.isEmpty()) add("no blocks")
    if (words > persona.words * WORD_SLACK) add("$words words > ${persona.words}")
}

/** What was said: the facts asked for, the language, memory, no leftovers of another voice. */
private fun contentProblems(text: String, reply: String, case: EvalCase, oldVoice: String?) = buildList {
    for (any in case.mustContain) {
        if (any.none { text.contains(it, ignoreCase = true) || reply.contains(it) }) add("missing ${any.first()}")
    }
    val words = text.replace(MATH, " ")
    if (case.norwegian && words.split(' ').count { it.isNotBlank() } > 2 && !NORWEGIAN.containsMatchIn(words)) add("not Norwegian")
    if (!case.norwegian && FOREIGN.findAll(words).count() >= FOREIGN_WORDS) add("not English")
    val remembered = parseMemoryOps(reply).any { it is MemoryOp.Remember }
    if (case.remembers == true && !remembered) add("remembered nothing")
    if (case.remembers == false && remembered) add("remembered a joke")
    oldVoice?.let { old -> VOICE_MARKERS[old].orEmpty().filter { reply.contains(it) }.forEach { add("old voice: \"$it\"") } }
}

private const val WORD_SLACK = 1.4
private const val FOREIGN_WORDS = 3
