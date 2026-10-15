package com.nisaba.persona

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

/** Changes the voice of an open notebook: [current] is picked to start with; [onPick] gets the new one. */
@Composable
fun VoiceDialogComponent(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var picked by remember(current) { mutableStateOf(current) }
    NisabaDialog("Who answers here?", onDismiss) {
        PersonaPickerComponent(picked, { picked = it })
        NisabaButtonRow {
            NisabaButton("Cancel", onDismiss)
            NisabaButton("Use this voice", { onPick(picked) }, filled = true)
        }
    }
}
