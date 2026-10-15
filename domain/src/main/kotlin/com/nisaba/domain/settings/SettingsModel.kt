package com.nisaba.domain.settings

import com.nisaba.domain.ink.EraseMode
import com.nisaba.domain.persona.PersonaRegistry

enum class HandSpeed { Slow, Natural, Swift }
enum class HandStyle { Casual, Formal }
enum class AnswerInk { Lapis, Black }

/** How code in answers is marked: coloured pens, pen weight only (bold keywords, thin comments), or not at all. */
enum class CodeHighlight { Colour, Weight, Off }

/** The user's pen thickness; [width] in page units (Medium = the stroke width ink has always had). */
enum class PenSize(val width: Float) { Fine(2.4f), Medium(3.5f), Bold(5.5f) }

data class Settings(
    val pauseMillis: Int = 3500,
    val handSpeed: HandSpeed = HandSpeed.Natural,
    val handStyle: HandStyle = HandStyle.Casual,
    val answerInk: AnswerInk = AnswerInk.Lapis,
    val model: String = "deepseek-flash",
    val thinking: Boolean = false,
    val listeningDefault: Boolean = true,
    val eraserMode: EraseMode = EraseMode.Stroke,
    val onboarded: Boolean = false,
    val hintSeen: Boolean = false,
    val codeHighlight: CodeHighlight = CodeHighlight.Colour,
    /** The user's own pen: its ink (ARGB) and thickness. */
    val penInk: Int = 0xFF111111.toInt(),
    val penSize: PenSize = PenSize.Medium,
    /** The voice the last new notebook got; the picker starts there. */
    val lastPersona: String = PersonaRegistry.DEFAULT_ID,
) {
    companion object {
        const val MIN_PAUSE = 2000
        const val MAX_PAUSE = 6000
    }
}
