package com.nisaba.settings

import com.nisaba.domain.settings.Settings

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
)
