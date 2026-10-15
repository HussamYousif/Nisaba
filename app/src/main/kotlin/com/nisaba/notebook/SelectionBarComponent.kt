package com.nisaba.notebook

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nisaba.canvas.PEN_INKS
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton

/** How tall the bar is at most (inks shown), so the pen keeps clear of it. */
const val SELECTION_BAR_HEIGHT = 150
private const val SWATCH = 36

/**
 * What can be done with the lassoed ink: a row of plain buttons along the bottom, out of the pen's way. A loop
 * around printed text only (nothing of theirs inside) can only be asked about.
 */
@Composable
fun SelectionBarComponent(sel: InkSelection, ai: Boolean, vm: NotebookViewModel, modifier: Modifier = Modifier) {
    val c = Nisaba.colors
    val ink = sel.ids.isNotEmpty()
    Column(
        modifier.fillMaxWidth().background(c.paper).border(BorderStroke(1.dp, c.ink)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (sel.colours) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((_, colour) in PEN_INKS) {
                    val swatch = Modifier.size(SWATCH.dp).background(Color(colour.argb)).border(BorderStroke(1.dp, c.ink))
                    Box(swatch.clickable { vm.recolourSelection(colour.argb) })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ai) NisabaButton("Ask about it", vm::askAboutSelection)
            if (ai && ink) { NisabaButton("Check it", vm::checkSelection); NisabaButton("Redraw cleanly", vm::redrawSelection) }
            if (ink) { NisabaButton("Colour", vm::toggleSelectionColours); NisabaButton("Delete", vm::deleteSelection) }
            Spacer(Modifier.weight(1f))
            NisabaButton("Done", vm::clearSelection, filled = true)
        }
        Text(
            if (ink) "Drag inside the box with the pen to move it." else "A loop around printed text: ask about it.",
            style = MaterialTheme.typography.labelSmall, color = c.faint,
        )
    }
}
