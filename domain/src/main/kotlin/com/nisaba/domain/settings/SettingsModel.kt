package com.nisaba.domain.settings

import com.nisaba.domain.ink.EraseMode

enum class HandSpeed { Slow, Natural, Swift }
enum class HandStyle { Casual, Formal }
enum class AnswerInk { Lapis, Black }

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
) {
    companion object {
        const val MIN_PAUSE = 2000
        const val MAX_PAUSE = 6000
    }
}
