package com.nisaba.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton

private const val CRASH_LINES = 4

/** This build, and the last crash kept on the device: its first lines, Copy (all of it, to send on) and Clear. */
@Composable
internal fun AboutPane(state: SettingsUiState, vm: SettingsViewModel) {
    val c = Nisaba.colors
    Text("Nisaba ${state.build}", style = MaterialTheme.typography.bodyLarge)
    val crash = state.lastCrash
    if (crash == null) {
        Text("No crash recorded on this device.", style = MaterialTheme.typography.bodyLarge, color = c.faint)
        return
    }
    Text("The last crash:", style = MaterialTheme.typography.bodyLarge)
    Text(
        crash.lines().take(CRASH_LINES).joinToString("\n"), style = MaterialTheme.typography.labelLarge, color = c.faint,
        maxLines = CRASH_LINES, overflow = TextOverflow.Ellipsis,
    )
    val clipboard = LocalClipboardManager.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NisabaButton("Copy", { clipboard.setText(AnnotatedString(crash)) })
        NisabaButton("Clear", vm::clearCrash)
    }
}
