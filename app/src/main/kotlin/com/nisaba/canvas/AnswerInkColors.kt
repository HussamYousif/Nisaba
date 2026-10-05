package com.nisaba.canvas

import com.nisaba.domain.ink.InkColor
import com.nisaba.domain.settings.AnswerInk
import com.nisaba.domain.settings.CodeHighlight
import com.nisaba.scribe.layout.InkTone

/** One of the hand's pens: its ink, and how wide it writes relative to the normal answer pen. */
data class AnswerPen(val color: InkColor, val widthScale: Float)

/** Saturated on purpose: Kaleido washes colour out. Tune on the panel (swatch: an answer with code). */
private val KEYWORD_RED = InkColor(0xFFA3271B.toInt())
private val LITERAL_GREEN = InkColor(0xFF1F7A33.toInt())
private val COMMENT_GREY = InkColor(0xFF77736C.toInt())
private const val COMMENT_WIDTH = 0.7f // a pencil, not a pen
private const val BOLD_WIDTH = 1.5f

/**
 * The pens the hand writes with: the answer ink, and inside code a pen per highlighter tone. [style] = how code is
 * marked: coloured pens; weight only (keywords bold, comments a thin grey pencil); or not at all.
 */
fun answerPen(tone: InkTone, ink: AnswerInk, style: CodeHighlight = CodeHighlight.Colour): AnswerPen {
    val plain = AnswerPen(if (ink == AnswerInk.Black) InkColor.BLACK else InkColor.LAPIS, 1f)
    return when (style) {
        CodeHighlight.Off -> plain
        CodeHighlight.Weight -> when (tone) {
            InkTone.Keyword -> plain.copy(widthScale = BOLD_WIDTH)
            InkTone.Comment -> AnswerPen(COMMENT_GREY, COMMENT_WIDTH)
            InkTone.Plain, InkTone.Literal -> plain
        }
        CodeHighlight.Colour -> when (tone) {
            InkTone.Plain -> plain
            InkTone.Keyword -> AnswerPen(KEYWORD_RED, 1f)
            InkTone.Literal -> AnswerPen(LITERAL_GREEN, 1f)
            InkTone.Comment -> AnswerPen(COMMENT_GREY, COMMENT_WIDTH)
        }
    }
}

/** The user's own inks, picked to stay distinct on a Kaleido panel. */
val PEN_INKS: List<Pair<String, InkColor>> = listOf(
    "Black" to InkColor.BLACK,
    "Lapis" to InkColor(0xFF27407A.toInt()),
    "Red" to InkColor(0xFFC0281C.toInt()),
    "Green" to InkColor(0xFF1F8A3A.toInt()),
    "Orange" to InkColor(0xFFE07A10.toInt()),
    "Purple" to InkColor(0xFF6B2FA0.toInt()),
    "Teal" to InkColor(0xFF0E7C86.toInt()),
    "Brown" to InkColor(0xFF7A4A21.toInt()),
)
