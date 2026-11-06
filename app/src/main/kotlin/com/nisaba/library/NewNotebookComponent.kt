package com.nisaba.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nisaba.persona.LocalVoices
import com.nisaba.persona.AnswerStyleComponent
import com.nisaba.persona.PersonaPickerComponent
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaButtonRow
import com.nisaba.ui.theme.NisabaDialog
import com.nisaba.ui.theme.NisabaTextField

/** The steps of "New": what kind → who answers → a name, or the PDF (system picker). */
@Composable
fun NewNotebookComponent(dialog: LibraryDialog, actions: LibraryActions) {
    val onDismiss = actions.onDismiss
    when (val d = dialog) {
        LibraryDialog.ChooseKind -> NisabaDialog("New", onDismiss) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (kind in NotebookKind.entries) KindRow(kind) { actions.onChooseKind(kind) }
            }
            NisabaButtonRow { NisabaButton("Cancel", onDismiss) }
        }
        is LibraryDialog.ChooseVoice -> NisabaDialog("Who answers?", onDismiss) {
            PersonaPickerComponent(d.personaId, actions.onSelectVoice)
            AnswerStyleComponent(d.style, actions.onSelectStyle)
            val pdf = d.kind == NotebookKind.Pdf || d.kind == NotebookKind.Book
            NisabaButtonRow {
                NisabaButton("‹ Back", actions.onBack)
                NisabaButton(if (d.handed) "Import" else if (pdf) "Choose the PDF" else "Next", actions.onConfirmVoice, filled = true)
            }
        }
        is LibraryDialog.NameIt -> {
            var name by remember { mutableStateOf("") }
            NisabaDialog(if (d.kind == NotebookKind.Canvas) "New blank canvas" else "New notebook", onDismiss, keepOnOutsideTap = true) {
                NisabaTextField(name, { name = it }, onDone = { actions.onCreate(name) })
                VoiceLine(d.personaId)
                NisabaButtonRow {
                    NisabaButton("‹ Back", actions.onBack)
                    NisabaButton("Create", { actions.onCreate(name) }, filled = true)
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun KindRow(kind: NotebookKind, onClick: () -> Unit) {
    val c = Nisaba.colors
    val row = Modifier.fillMaxWidth().border(BorderStroke(1.5.dp, c.ink)).clickable(onClick = onClick)
    Column(row.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(kind.label, style = MaterialTheme.typography.titleMedium)
        Text(kind.hint, style = MaterialTheme.typography.labelSmall, color = c.faint)
    }
}

@Composable
private fun VoiceLine(personaId: String) =
    Text("Answered by ${LocalVoices.current.of(personaId).name}", style = MaterialTheme.typography.labelSmall, color = Nisaba.colors.faint)
