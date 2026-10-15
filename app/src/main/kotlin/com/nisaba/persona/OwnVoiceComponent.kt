package com.nisaba.persona

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.nisaba.domain.persona.Voices
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaButtonRow
import com.nisaba.ui.theme.NisabaDialog
import com.nisaba.ui.theme.NisabaTextField

/** Every voice, the person's own included (provided at the root, kept current). */
val LocalVoices = compositionLocalOf { Voices() }

/** Drafting a voice of their own (null where it isn't offered: previews, tests). */
val LocalOwnVoices = staticCompositionLocalOf<OwnVoiceViewModel?> { null }

/**
 * "A voice of your own", above whatever dialog asked for it: they describe someone ("a grumpy pirate who loves
 * maths"), the model drafts the voice, the hand writes its first line in its ink, and they keep it or try again.
 */
@Composable
fun OwnVoiceHost(vm: OwnVoiceViewModel) {
    val s by vm.state.collectAsState()
    if (!s.open) return
    val c = Nisaba.colors
    Box(Modifier.fillMaxSize().background(c.paper)) // a clean page: the picker underneath would show through
    NisabaDialog("A voice of your own", vm::close) {
        val d = s.draft
        if (d == null) {
            Text(
                "Describe who you'd like to hear from: a character, a kind of person, a mood.",
                style = MaterialTheme.typography.bodyLarge,
            )
            NisabaTextField(s.description, vm::describe, onDone = vm::draft)
        } else {
            Text(d.name, style = MaterialTheme.typography.titleMedium)
            if (d.tagline.isNotBlank()) Text(d.tagline, style = MaterialTheme.typography.labelSmall, color = c.faint)
            val inks = com.nisaba.domain.persona.PersonaRegistry.OWN_INKS
            VoiceSampleComponent(d.sample, Color(inks[LocalVoices.current.own.size % inks.size]))
        }
        when {
            s.drafting -> Text("Finding the voice…", style = MaterialTheme.typography.bodyLarge, color = c.faint)
            s.error != null -> Text(s.error.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = c.lapis)
        }
        NisabaButtonRow {
            NisabaButton("Cancel", vm::close)
            if (d == null) {
                NisabaButton("Draft it", vm::draft, filled = true)
            } else {
                NisabaButton("Try again", vm::draft)
                NisabaButton("Keep this voice", vm::keep, filled = true)
            }
        }
    }
}
