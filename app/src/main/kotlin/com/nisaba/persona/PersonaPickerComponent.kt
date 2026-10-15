package com.nisaba.persona

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.nisaba.domain.notebook.AnswerLength
import com.nisaba.domain.notebook.AnswerMode
import com.nisaba.domain.notebook.AnswerStyle
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.ui.brand.HandTextComponent
import com.nisaba.ui.brand.LocalHandText
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaButtonRow
import com.nisaba.ui.theme.NisabaDialog

/** Where the hand wraps a sample (page units): every sample fits in two lines, a full line fills the row. */
private const val SAMPLE_LINE = 1800f
private const val SAMPLE_HEIGHT = 84

/**
 * Every voice, one row each (no scrolling on e-ink), the picked one filled in ink. Below, the hand writes a line in
 * the picked voice, so the person hears it before choosing.
 */
@Composable
fun PersonaPickerComponent(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Nisaba.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in PersonaRegistry.all) {
            val on = p.id == selected
            Column(
                Modifier.fillMaxWidth().then(if (on) Modifier.background(c.ink) else Modifier)
                    .border(BorderStroke(1.5.dp, c.ink)).clickable { onSelect(p.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, color = if (on) c.paper else c.ink)
                Text(p.tagline, style = MaterialTheme.typography.labelSmall, color = if (on) c.paper else c.faint)
            }
        }
        val sample = PersonaRegistry.of(selected).sample
        val width = LocalHandText.current?.of(sample, SAMPLE_LINE)?.width ?: SAMPLE_LINE // same letter size, short or long
        Box(Modifier.fillMaxWidth().height(SAMPLE_HEIGHT.dp).padding(top = 10.dp)) {
            val fit = Modifier.fillMaxWidth((width / SAMPLE_LINE).coerceIn(0.1f, 1f))
            HandTextComponent(sample, c.lapis, fit, animate = true, lineWidth = SAMPLE_LINE)
        }
    }
}

/** When Nisaba answers and how long its answers are: two short rows of choices (small buttons, no scrolling). */
@Composable
fun AnswerStyleComponent(style: AnswerStyle, onChange: (AnswerStyle) -> Unit) {
    val label = MaterialTheme.typography.labelSmall
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Answers", style = label, modifier = Modifier.width(STYLE_LABEL.dp))
        for ((mode, name) in MODES) SmallChoice(name, style.mode == mode) { onChange(style.copy(mode = mode)) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Length", style = label, modifier = Modifier.width(STYLE_LABEL.dp))
        for (len in AnswerLength.entries) SmallChoice(len.name, style.length == len) { onChange(style.copy(length = len)) }
    }
}

private val MODES = listOf(
    AnswerMode.Everything to "Everything", AnswerMode.QuestionsOnly to "Questions only", AnswerMode.WhenAsked to "When I ask",
)
private const val STYLE_LABEL = 64

@Composable
private fun SmallChoice(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Nisaba.colors
    Box(
        Modifier.then(if (on) Modifier.background(c.ink) else Modifier).border(BorderStroke(1.dp, c.ink)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) c.paper else c.ink) }
}

/** Changes the voice and answering habits of an open notebook; [onPick] gets the new ones. */
@Composable
fun VoiceDialogComponent(current: String, style: AnswerStyle, onPick: (String, AnswerStyle) -> Unit, onDismiss: () -> Unit) {
    var picked by remember(current) { mutableStateOf(current) }
    var habits by remember(style) { mutableStateOf(style) }
    NisabaDialog("Who answers here?", onDismiss) {
        PersonaPickerComponent(picked, { picked = it })
        AnswerStyleComponent(habits) { habits = it }
        NisabaButtonRow {
            NisabaButton("Cancel", onDismiss)
            NisabaButton("Use this voice", { onPick(picked, habits) }, filled = true)
        }
    }
}
