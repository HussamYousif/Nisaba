package com.nisaba.persona

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import com.nisaba.domain.persona.Persona
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
import androidx.compose.foundation.layout.heightIn
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
 * Every voice in two columns (no scrolling on e-ink), each with its sign, the picked one filled in ink; then "a voice
 * of your own" while there is room. Below, the hand writes a line in the picked voice, so they hear it before choosing.
 */
@Composable
fun PersonaPickerComponent(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Nisaba.colors
    val voices = LocalVoices.current
    val own = LocalOwnVoices.current
    val kept = own?.state?.collectAsState()?.value?.kept
    LaunchedEffect(kept) { if (kept != null) { onSelect(kept); own.consumeKept() } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val cells: List<Persona?> = voices.all + if (own != null && voices.canAddOwn) listOf(null) else emptyList()
        for (row in cells.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in row) {
                    val cell = Modifier.weight(1f)
                    if (p == null) OwnVoiceCell(cell) { own?.open() } else VoiceCell(p, p.id == selected, cell) { onSelect(p.id) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        val picked = voices.of(selected)
        VoiceSampleComponent(picked.sample, inkOf(picked, c.lapis))
        if (own != null && PersonaRegistry.isOwn(selected) && voices.own.any { it.id == selected }) {
            // its notebooks go back to The sassy book: a second tap, so a stray one never does it
            com.nisaba.ui.theme.NisabaConfirmButton(
                "Forget this voice", "Forget it? Its notebooks get The sassy book",
                { own.forget(selected); onSelect(PersonaRegistry.DEFAULT_ID) },
            )
        }
    }
}

/** The hand writes a voice's [sample] line in its [ink]: the same letter size whether the line is short or long. */
@Composable
fun VoiceSampleComponent(sample: String, ink: Color) {
    val width = LocalHandText.current?.of(sample, SAMPLE_LINE)?.width ?: SAMPLE_LINE
    Box(Modifier.fillMaxWidth().height(SAMPLE_HEIGHT.dp).padding(top = 10.dp)) {
        val fit = Modifier.fillMaxWidth((width / SAMPLE_LINE).coerceIn(0.1f, 1f))
        HandTextComponent(sample, ink, fit, animate = true, lineWidth = SAMPLE_LINE)
    }
}

/** The ink a voice writes in (the answer ink when it has none of its own). */
fun inkOf(voice: Persona, answerInk: Color): Color = voice.hand.ink?.let { Color(it) } ?: answerInk

@Composable
private fun VoiceCell(p: Persona, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Nisaba.colors
    Row(
        modifier.then(if (on) Modifier.background(c.ink) else Modifier).border(BorderStroke(1.5.dp, c.ink))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val sign = if (on) c.paper else inkOf(p, c.lapis)
        Canvas(Modifier.size(SIGIL.dp)) { drawSigil(p, sign, center, size.minDimension / 2f) }
        Column {
            Text(
                p.name, style = MaterialTheme.typography.titleMedium, color = if (on) c.paper else c.ink, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                p.tagline, style = MaterialTheme.typography.labelSmall, color = if (on) c.paper else c.faint, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OwnVoiceCell(modifier: Modifier, onClick: () -> Unit) {
    val c = Nisaba.colors
    Column(modifier.border(BorderStroke(1.5.dp, c.faint)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text("+ A voice of your own", style = MaterialTheme.typography.titleMedium)
        Text("Describe someone; Nisaba finds their voice.", style = MaterialTheme.typography.labelSmall, color = c.faint, maxLines = 2)
    }
}

private const val SIGIL = 28

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
private const val CHOICE_MIN_DP = 44

@Composable
private fun SmallChoice(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Nisaba.colors
    Box(
        Modifier.then(if (on) Modifier.background(c.ink) else Modifier).border(BorderStroke(1.dp, c.ink)).clickable(onClick = onClick)
            .heightIn(min = CHOICE_MIN_DP.dp).padding(horizontal = 12.dp, vertical = 6.dp), // a finger's width, not a word's
        contentAlignment = Alignment.Center,
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
