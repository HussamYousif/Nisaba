package com.nisaba.notebook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nisaba.domain.conversation.AgainHow
import com.nisaba.persona.PersonaPickerComponent
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaButtonRow
import com.nisaba.ui.theme.NisabaDialog

/**
 * A finger held on an answer: erase it, another go (afresh, shorter, deeper, an example, another voice), copy its
 * words, or report it. The second opinion picks its voice in place.
 */
@Composable
fun AnswerMenuComponent(m: AnswerMenu, vm: NotebookViewModel) {
    if (m.pickVoice) {
        var picked by remember { mutableStateOf(m.otherVoice) }
        NisabaDialog("Who else should answer?", vm::dismissAnswerMenu) {
            PersonaPickerComponent(picked, { picked = it })
            NisabaButtonRow {
                NisabaButton("Cancel", vm::dismissAnswerMenu)
                NisabaButton("Ask", { vm.askAgain(m.exchangeId, AgainHow.OtherVoice(picked)) }, filled = true)
            }
        }
        return
    }
    NisabaDialog("This answer", vm::dismissAnswerMenu) {
        NisabaButton("Erase answer", { vm.eraseAnswer(m.exchangeId) }, Modifier.fillMaxWidth())
        if (m.canAskAgain) {
            NisabaButton("Ask again", { vm.askAgain(m.exchangeId) }, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NisabaButton("Shorter", { vm.askAgain(m.exchangeId, AgainHow.Shorter) }, Modifier.weight(1f))
                NisabaButton("Deeper", { vm.askAgain(m.exchangeId, AgainHow.Deeper) }, Modifier.weight(1f))
                NisabaButton("An example", { vm.askAgain(m.exchangeId, AgainHow.Example) }, Modifier.weight(1f))
            }
            NisabaButton("Ask another voice…", vm::pickOtherVoice, Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NisabaButton("Copy the words", { vm.copyAnswer(m.exchangeId) }, Modifier.weight(1f))
            NisabaButton("Share…", { vm.reportAnswer(m.exchangeId) }, Modifier.weight(1f))
        }
        NisabaButton("Cancel", vm::dismissAnswerMenu, Modifier.fillMaxWidth())
    }
}
