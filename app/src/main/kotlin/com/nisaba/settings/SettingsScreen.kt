package com.nisaba.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nisaba.domain.settings.AnswerInk
import com.nisaba.domain.settings.HandSpeed
import com.nisaba.domain.settings.HandStyle
import com.nisaba.domain.settings.Settings
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaTextField

private const val PAUSE_STEP = 500

@Composable
fun SettingsScreen(state: SettingsUiState, vm: SettingsViewModel, onBack: () -> Unit) {
    val c = Nisaba.colors
    val s = state.settings
    Column(
        Modifier.fillMaxSize().background(c.paper).verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            NisabaButton("Done", onBack, filled = true)
        }
        Section("DeepSeek key") {
            val note = if (state.hasKey) "A key is saved on this device." else "No key yet. Paste one to let Nisaba answer."
            Text(note, style = MaterialTheme.typography.bodyLarge)
            NisabaTextField(state.keyInput, vm::onKeyInput, onDone = vm::saveKey, autoFocus = false)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                NisabaButton("Save key", vm::saveKey, enabled = state.keyInput.isNotBlank())
                NisabaButton("Test key", vm::testKey, enabled = state.hasKey && state.keyCheck != KeyCheck.Testing)
                Text(
                    when (val k = state.keyCheck) {
                        KeyCheck.Idle -> ""
                        KeyCheck.Testing -> "Asking…"
                        KeyCheck.Ok -> "✓ the ink flows"
                        is KeyCheck.Failed -> "✗ ${k.message}"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Section("Model") {
            Choice(listOf("deepseek-flash" to "Flash (fast)", "deepseek-v4-pro" to "Pro (slower, deeper)"), s.model, vm::setModel)
            Toggle("Think before answering (slower)", s.thinking, vm::setThinking)
        }
        Section("Waiting") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NisabaButton("−", { vm.setPause(s.pauseMillis - PAUSE_STEP) }, enabled = s.pauseMillis > Settings.MIN_PAUSE)
                Text("%.1f seconds of quiet before it answers".format(s.pauseMillis / 1000f), style = MaterialTheme.typography.bodyLarge)
                NisabaButton("+", { vm.setPause(s.pauseMillis + PAUSE_STEP) }, enabled = s.pauseMillis < Settings.MAX_PAUSE)
            }
        }
        Section("The hand") {
            Choice(HandSpeed.entries.map { it to it.name }, s.handSpeed, vm::setSpeed)
            Choice(listOf(HandStyle.Casual to "Joined (clear)", HandStyle.Formal to "Full cursive (pretty)"), s.handStyle, vm::setStyle)
            Choice(listOf(AnswerInk.Lapis to "Lapis blue", AnswerInk.Black to "Black"), s.answerInk, vm::setInk)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        content()
    }
}

@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((value, label) in options) NisabaButton(label, { onSelect(value) }, filled = value == selected)
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        NisabaButton(if (on) "On" else "Off", { onChange(!on) }, filled = on)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
