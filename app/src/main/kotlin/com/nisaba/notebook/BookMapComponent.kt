package com.nisaba.notebook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nisaba.domain.book.BookMap
import com.nisaba.domain.book.BookReading
import com.nisaba.domain.book.BookStep
import com.nisaba.domain.lesson.LessonTopic
import com.nisaba.domain.lesson.TopicStatus
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaButtonRow
import com.nisaba.ui.theme.NisabaDialog

/**
 * The map of a book walk: its argument, then each concept under its part of the book (✓ walked: tap to go back to its
 * page; › next: tap to walk it; how they did when last asked) and the whole picture at the end. A long map shows a
 * screenful at a time (no scrolling on e-ink), opening on the screen with the next step.
 */
@Composable
fun BookMapComponent(map: BookMap, topics: List<LessonTopic>, vm: NotebookViewModel) {
    val c = Nisaba.colors
    val screens = remember(map) { mapRows(map).chunkedKeepingParts(ROWS_PER_SCREEN) }
    var shown by remember(map) { mutableIntStateOf(screens.indexOfFirst { s -> s.any { it.isNext(map) } }.coerceAtLeast(0)) }
    val judged = remember(topics) { topics.associate { it.topic.lowercase() to it.status } }
    NisabaDialog(map.title, vm::closeBookMap, maxWidth = 720.dp) {
        if (map.thesis.isNotBlank()) {
            Text(map.thesis, style = MaterialTheme.typography.bodyLarge, color = c.faint, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Text(progressLine(map, judged), style = MaterialTheme.typography.labelLarge, color = c.faint)
        Column { // rows close together: a screenful of the map fits without scrolling
            screens.getOrNull(shown).orEmpty().forEach { row ->
                when (row) {
                    is MapRow.Part -> Text(
                        row.name, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    is MapRow.Step -> StepRow(map, row.index, judged, vm)
                }
            }
        }
        if (screens.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                NisabaButton("‹ Earlier", { shown-- }, enabled = shown > 0)
                Text("${shown + 1} of ${screens.size}", style = MaterialTheme.typography.labelLarge)
                NisabaButton("Later ›", { shown++ }, enabled = shown < screens.size - 1)
            }
        }
        NisabaButtonRow { NisabaButton("Close", vm::closeBookMap) }
    }
}

/** One step of the walk: concept [index], or the whole picture (null). */
@Composable
private fun StepRow(map: BookMap, index: Int?, judged: Map<String, TopicStatus>, vm: NotebookViewModel) {
    val concept = index?.let { map.concepts[it] }
    val page = if (concept != null) concept.pageId else map.synthesisPageId
    val here = MapRow.Step(index).isNext(map)
    val status = when (concept?.let { judged[it.title.lowercase()] }) {
        TopicStatus.Learned -> "learned"
        TopicStatus.Shaky -> "go over"
        null -> ""
    }
    StepLine(
        mark = if (page != null) "✓" else if (here) "›" else "",
        label = concept?.let { "${index + 1}. ${it.title}" } ?: "The whole picture", status = status,
        pages = concept?.pages.orEmpty(), active = page != null || here,
    ) { if (page != null) vm.openBookPage(page) else { vm.closeBookMap(); vm.nextBookStep() } }
}

@Composable
@Suppress("LongParameterList") // one line of the map: each thing it shows
private fun StepLine(mark: String, label: String, status: String, pages: String, active: Boolean, onTap: () -> Unit) {
    val c = Nisaba.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = ROW_DP.dp).clickable(enabled = active, onClick = onTap),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(mark, Modifier.width(MARK_DP.dp), style = MaterialTheme.typography.titleMedium)
        Text(
            label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (active) c.ink else c.faint,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.labelLarge, color = if (status == "go over") c.ink else c.faint)
        }
        if (pages.isNotEmpty()) Text(pages, style = MaterialTheme.typography.labelLarge, color = c.faint)
    }
}

/** A line of the map dialog: a part of the book, or a step of the walk (a concept by index; null = the whole picture). */
internal sealed interface MapRow {
    data class Part(val name: String) : MapRow
    data class Step(val index: Int?) : MapRow
}

private fun MapRow.isNext(map: BookMap): Boolean = this is MapRow.Step && when (val n = map.next) {
    is BookStep.Concept -> index == n.index
    BookStep.Synthesis -> index == null
    else -> false
}

/** The map's lines: each part's name before its first concept, then the concepts, then the whole picture. */
internal fun mapRows(map: BookMap): List<MapRow> = buildList {
    map.concepts.forEachIndexed { i, c ->
        if (c.part.isNotEmpty() && c.part != map.concepts.getOrNull(i - 1)?.part) add(MapRow.Part(c.part))
        add(MapRow.Step(i))
    }
    add(MapRow.Step(null))
}

/** Screens of at most [size] lines; a part's name never ends a screen (it moves to the next, with its first concept). */
internal fun List<MapRow>.chunkedKeepingParts(size: Int): List<List<MapRow>> {
    val screens = ArrayList<List<MapRow>>()
    var rest = this
    while (rest.isNotEmpty()) {
        var take = minOf(size, rest.size)
        if (take < rest.size && rest[take - 1] is MapRow.Part && take > 1) take--
        screens += rest.take(take); rest = rest.drop(take)
    }
    return screens
}

/** "12 of 40 walked · 9 learned · 2 to go over". */
internal fun progressLine(map: BookMap, judged: Map<String, TopicStatus>): String {
    val statuses = map.concepts.mapNotNull { judged[it.title.lowercase()] }
    val learned = statuses.count { it == TopicStatus.Learned }
    val shaky = statuses.count { it == TopicStatus.Shaky }
    return buildString {
        append("${map.walked} of ${map.concepts.size} walked")
        if (learned > 0) append(" · $learned learned")
        if (shaky > 0) append(" · $shaky to go over")
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

/** Lines of the map on one screen (a short book's map fits on one). */
private const val ROWS_PER_SCREEN = 18
