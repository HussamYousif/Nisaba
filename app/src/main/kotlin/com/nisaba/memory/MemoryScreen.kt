package com.nisaba.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nisaba.domain.memory.FactCategory
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaButtonRow
import com.nisaba.ui.theme.NisabaDialog

private fun FactCategory.label() = when (this) {
    FactCategory.Me -> "About you"
    FactCategory.People -> "People"
    FactCategory.Projects -> "Projects"
    FactCategory.Books -> "Books"
    FactCategory.Screen -> "Film & TV"
    FactCategory.Music -> "Music"
    FactCategory.Preferences -> "Preferences"
    FactCategory.Goals -> "Goals"
    FactCategory.Other -> "Other"
}

@Composable
fun MemoryScreen(state: MemoryUiState, vm: MemoryViewModel, onBack: () -> Unit) {
    val c = Nisaba.colors
    Box(Modifier.fillMaxSize().background(c.paper)) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("What Nisaba remembers", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                NisabaButton("Done", onBack, filled = true)
            }
            Text(
                when {
                    state.loading -> ""
                    state.total == 0 -> "Nothing yet. Tell it about yourself in any notebook: what you are working on, " +
                        "books and films you love. It remembers across all notebooks."
                    else -> (if (state.total == 1) "1 thing" else "${state.total} things") +
                        ", learned from what you wrote. Every notebook sees them; " +
                        "they leave this device only with your questions."
                },
                style = MaterialTheme.typography.bodyLarge, color = c.faint,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (row in state.rows) {
                    if (row.header) {
                        Spacer(Modifier.height(8.dp))
                        Text(row.category.label(), style = MaterialTheme.typography.titleLarge)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(row.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        NisabaButton("Forget", { vm.askForget(row) })
                    }
                }
            }
            if (state.pageCount > 1) {
                Row(
                    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                ) {
                    NisabaButton("‹ Earlier", vm::previous, enabled = state.page > 0)
                    Text("${state.page + 1} of ${state.pageCount}", style = MaterialTheme.typography.bodyLarge)
                    NisabaButton("Later ›", vm::next, enabled = state.page < state.pageCount - 1)
                }
            }
        }
        state.confirming?.let { row ->
            NisabaDialog("Forget this?", vm::cancelForget) {
                Text(row.text, style = MaterialTheme.typography.bodyLarge)
                NisabaButtonRow {
                    NisabaButton("Keep", vm::cancelForget)
                    NisabaButton("Forget", vm::confirmForget, filled = true)
                }
            }
        }
    }
}
