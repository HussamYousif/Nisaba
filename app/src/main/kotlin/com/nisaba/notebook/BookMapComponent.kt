package com.nisaba.notebook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nisaba.domain.book.BookMap
import com.nisaba.domain.book.BookReading
import com.nisaba.domain.book.BookStep
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaButtonRow
import com.nisaba.ui.theme.NisabaDialog

/**
 * The map of a book walk: its argument, then each concept (✓ walked: tap to go back to its page; › next: tap to walk it)
 * and the whole picture at the end.
 */
@Composable
fun BookMapComponent(map: BookMap, vm: NotebookViewModel) {
    val c = Nisaba.colors
    NisabaDialog(map.title, vm::closeBookMap, maxWidth = 720.dp) {
        if (map.thesis.isNotBlank()) {
            Text(map.thesis, style = MaterialTheme.typography.bodyLarge, color = c.faint, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        val next = map.next
        Column { // rows close together: a long map still fits the screen (no scrolling on e-ink)
            map.concepts.forEachIndexed { i, concept ->
                val here = next == BookStep.Concept(i)
                val page = concept.pageId
                StepRow(
                    mark = if (page != null) "✓" else if (here) "›" else "", label = "${i + 1}. ${concept.title}", pages = concept.pages,
                    active = page != null || here,
                ) { if (page != null) vm.openBookPage(page) else { vm.closeBookMap(); vm.nextBookStep() } }
            }
            val whole = map.synthesisPageId
            StepRow(
                if (whole != null) "✓" else if (next == BookStep.Synthesis) "›" else "", "The whole picture", "",
                active = whole != null || next == BookStep.Synthesis,
            ) { if (whole != null) vm.openBookPage(whole) else { vm.closeBookMap(); vm.nextBookStep() } }
        }
        NisabaButtonRow { NisabaButton("Close", vm::closeBookMap) }
    }
}

@Composable
private fun StepRow(mark: String, label: String, pages: String, active: Boolean, onTap: () -> Unit) {
    val c = Nisaba.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = ROW_DP.dp).clickable(enabled = active, onClick = onTap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(mark, Modifier.width(MARK_DP.dp), style = MaterialTheme.typography.titleMedium)
        Text(
            label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (active) c.ink else c.faint,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (pages.isNotEmpty()) Text(pages, style = MaterialTheme.typography.labelLarge, color = c.faint)
    }
}

/** One line on a book still being read: "Reading the book… 3 of 12 parts". */
internal fun readingLine(r: BookReading): String =
    if (r.parts <= 1) "Reading the book…" else "Reading the book… ${r.done} of ${r.parts} parts"

/** The menu's way on: the map of the book, the next concept, or the whole picture. */
internal fun nextLabel(map: BookMap, step: BookStep): String = when (step) {
    BookStep.Overview -> "Write the map of the book"
    is BookStep.Concept -> "Next: ${step.index + 1}. ${map.concepts[step.index].title} ›"
    BookStep.Synthesis -> "Next: the whole picture ›"
}

private const val ROW_DP = 40
private const val MARK_DP = 28
