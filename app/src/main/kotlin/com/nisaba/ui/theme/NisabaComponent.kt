package com.nisaba.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NisabaButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false, enabled: Boolean = true) {
    val c = Nisaba.colors
    val fg = if (filled) c.paper else if (enabled) c.ink else c.faint
    Box(
        modifier
            .then(if (filled) Modifier.background(c.ink) else Modifier)
            .border(BorderStroke(1.5.dp, if (enabled) c.ink else c.faint))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, style = androidx.compose.material3.MaterialTheme.typography.labelLarge) }
}

@Composable
fun NisabaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
    autoFocus: Boolean = true,
) {
    val c = Nisaba.colors
    val focus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (autoFocus) focus.requestFocus() }
    Column(modifier) {
        BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = true,
            textStyle = TextStyle(color = c.ink, fontSize = 20.sp),
            cursorBrush = SolidColor(c.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).padding(vertical = 8.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(c.ink))
    }
}

/**
 * Modal card drawn in-place (no window, no animation). No veil over the page: on e-ink a veil repaints the whole screen,
 * so only the card is drawn; a tap outside it still dismisses.
 */
@Composable
fun NisabaDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = Nisaba.colors
    Box(
        Modifier.fillMaxSize().clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(24.dp).background(c.paper).border(BorderStroke(2.dp, c.ink))
                .clickable(enabled = false) {}.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
fun NisabaButtonRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) { content() }
}
