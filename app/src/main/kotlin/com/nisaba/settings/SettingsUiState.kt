package com.nisaba.settings

import com.nisaba.domain.settings.Settings
import com.nisaba.domain.usage.UsageSummary

sealed interface KeyCheck {
    data object Idle : KeyCheck
    data object Testing : KeyCheck
    data object Ok : KeyCheck
    data class Failed(val message: String) : KeyCheck
}

data class SettingsUiState(
    val settings: Settings = Settings(),
    val hasKey: Boolean = false,
    val keyInput: String = "",
    val keyCheck: KeyCheck = KeyCheck.Idle,
    /** How many facts Nisaba remembers across notebooks. */
    val factCount: Int = 0,
    /** Answers and tokens this month and in total; null until loaded (or with no history). */
    val usage: UsageSummary? = null,
)
