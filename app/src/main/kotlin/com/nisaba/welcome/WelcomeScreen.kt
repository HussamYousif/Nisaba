package com.nisaba.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nisaba.settings.KeyCheck
import com.nisaba.settings.SettingsUiState
import com.nisaba.settings.SettingsViewModel
import com.nisaba.ui.brand.HandTextComponent
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaTextField

/** First run: what Nisaba does, what leaves the device, and the key. Shown once (`Settings.onboarded`). */
@Composable
fun WelcomeScreen(state: SettingsUiState, vm: SettingsViewModel, onDone: () -> Unit) {
    val c = Nisaba.colors
    Column(
        Modifier.fillMaxSize().background(c.paper).verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        HandTextComponent("Nisaba", c.lapis, Modifier.width(300.dp))
        Text("Write. Pause. It answers in handwriting.", style = MaterialTheme.typography.headlineMedium)
        Text(
            "When you stop writing for a few seconds, Nisaba looks at your page " +
                "and, if you asked something, writes the answer beneath your ink.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("What leaves this device", style = MaterialTheme.typography.titleLarge)
        Text(
            "Only when Nisaba answers: an image of the current page, its recent conversation and any PDF text go to DeepSeek, " +
                "using your own key. No account, no analytics, no other servers. Notebooks stay on this device; the key is kept encrypted.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("Your DeepSeek key", style = MaterialTheme.typography.titleLarge)
        NisabaTextField(state.keyInput, vm::onKeyInput, onDone = vm::saveKey, autoFocus = false)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NisabaButton("Save key", vm::saveKey, enabled = state.keyInput.isNotBlank())
            NisabaButton("Test key", vm::testKey, enabled = state.hasKey && state.keyCheck != KeyCheck.Testing)
            Text(
                when (val k = state.keyCheck) {
                    KeyCheck.Idle -> if (state.hasKey) "Key saved." else ""
                    KeyCheck.Testing -> "Asking…"
                    KeyCheck.Ok -> "✓ the ink flows"
                    is KeyCheck.Failed -> "✗ ${k.message}"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NisabaButton(if (state.hasKey) "Begin" else "Begin without a key", onDone, filled = true)
        }
        if (!state.hasKey) {
            Text(
                "You can add the key later in Settings; until then Nisaba is a plain notebook.",
                style = MaterialTheme.typography.labelSmall, color = c.faint,
            )
        }
    }
}
