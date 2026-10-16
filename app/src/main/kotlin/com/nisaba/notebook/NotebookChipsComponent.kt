package com.nisaba.notebook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nisaba.domain.lesson.TopicStatus
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaButtonRow
import com.nisaba.ui.theme.NisabaDialog

// Small things drawn over the page: the failure and note chips, the star handle, the lesson progress card.

@Composable
internal fun FailureChip(text: String, onRetry: () -> Unit) {
    val c = Nisaba.colors
    Row(
        Modifier.padding(12.dp).background(c.paper).border(BorderStroke(2.dp, c.lapis)).clickable(onClick = onRetry).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("?", style = MaterialTheme.typography.titleLarge, color = c.lapis)
        Text("$text · tap to retry", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun MemoryChip(text: String, onDismiss: () -> Unit, mark: String = "✎") {
    val c = Nisaba.colors
    Row(
        Modifier.padding(12.dp).background(c.paper).border(BorderStroke(2.dp, c.lapis)).clickable(onClick = onDismiss).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(mark, style = MaterialTheme.typography.titleLarge, color = c.lapis)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * What "Add" on a reminder does: the calendar app with it filled in; without one, Nisaba's own notification at the time
 * (asking for permission to notify the first time); failing that, the words on the clipboard.
 */
@Composable
internal fun rememberReminderHandler(vm: NotebookViewModel): (com.nisaba.domain.conversation.Reminder) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    var waiting by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.nisaba.domain.conversation.Reminder?>(null)
    }
    fun own(r: com.nisaba.domain.conversation.Reminder) =
        if (com.nisaba.device.ReminderDevice.schedule(context, r)) vm.reminderSet(r) else vm.reminderNotAdded(r)
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> waiting?.let { r -> if (granted) own(r) else vm.reminderNotAdded(r) }; waiting = null }
    return { r ->
        when {
            CalendarClient.add(context, r) -> Unit
            com.nisaba.device.ReminderDevice.canNotify(context) -> own(r)
            else -> { waiting = r; ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}

/** A reminder the reply offered: Add puts it in the calendar; × waves it away. */
@Composable
internal fun ReminderChip(text: String, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val c = Nisaba.colors
    Row(
        Modifier.padding(12.dp).background(c.paper).border(BorderStroke(2.dp, c.lapis)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("⏰", style = MaterialTheme.typography.titleLarge, color = c.lapis)
        Text(text, style = MaterialTheme.typography.bodyLarge)
        NisabaButton("Add", onAdd, filled = true)
        NisabaButton("×", onDismiss)
    }
}

/** What the tutor has recorded: learned topics, then the shaky ones it will come back to. */
@Composable
internal fun LessonProgressDialog(state: NotebookUiState, onClose: () -> Unit) {
    NisabaDialog("Lesson progress", onClose) {
        val learned = state.lessonTopics.filter { it.status == TopicStatus.Learned }
        val shaky = state.lessonTopics.filter { it.status == TopicStatus.Shaky }
        val body = MaterialTheme.typography.bodyLarge
        if (state.lessonTopics.isEmpty()) Text("Nothing recorded yet. Answer a question or two.", style = body)
        if (learned.isNotEmpty()) Text("Learned: " + learned.joinToString(", ") { it.topic }, style = body)
        if (shaky.isNotEmpty()) Text("To go over again: " + shaky.joinToString(", ") { it.topic }, style = body)
        NisabaButtonRow { NisabaButton("Close", onClose, filled = true) }
    }
}

/** What the star in the corner says while the bar is hidden: nothing, writing an answer, it failed, or muted. */
internal sealed interface StarBadge {
    data object Working : StarBadge
    data object Failed : StarBadge
    data object Muted : StarBadge

    /** A tool other than the pen is on (its icon): writing that leaves no ink is then no mystery. */
    data class Tool(val icon: com.nisaba.ui.theme.NisabaIcon) : StarBadge
}

/** The small Nisaba star in the corner while the bar is hidden: tap it to bring the bar back, hold it for a few tools. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ToolbarHandle(modifier: Modifier, badge: StarBadge?, onTap: () -> Unit, onHold: () -> Unit = {}) {
    val c = Nisaba.colors
    Box(modifier.padding(HANDLE_MARGIN.dp).size(HANDLE_SIZE.dp).combinedClickable(onClick = onTap, onLongClick = onHold)) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.nisaba.R.drawable.ic_mark), "Show the toolbar", Modifier.fillMaxSize(),
        )
        val corner = Modifier.align(Alignment.TopStart).size(BADGE.dp).background(c.paper)
        when (badge) {
            StarBadge.Working -> Text("…", corner, style = MaterialTheme.typography.labelLarge, color = c.lapis)
            StarBadge.Failed -> Text("?", corner, style = MaterialTheme.typography.labelLarge, color = c.ink)
            StarBadge.Muted -> com.nisaba.ui.theme.NisabaIconGlyph(com.nisaba.ui.theme.NisabaIcon.Muted, c.ink, corner)
            is StarBadge.Tool -> com.nisaba.ui.theme.NisabaIconGlyph(badge.icon, c.ink, corner)
            null -> Unit
        }
    }
}

private const val BADGE = 18

const val HANDLE_SIZE = 44
const val HANDLE_MARGIN = 16

/** The paper of this notebook's blank pages: blank, lined, grid or dots (answers keep to the lines on lined paper). */
@Composable
internal fun PaperDialogComponent(
    current: com.nisaba.domain.notebook.Paper,
    onPick: (com.nisaba.domain.notebook.Paper) -> Unit,
    onClose: () -> Unit,
) {
    NisabaDialog("Paper", onClose) {
        for (p in com.nisaba.domain.notebook.Paper.entries) {
            NisabaButton(p.name, { onPick(p) }, Modifier.fillMaxWidth(), filled = p == current)
        }
    }
}

/** Held star: pen, eraser, undo and listening within reach, above the star, without the whole bar. */
@Composable
internal fun MiniBarComponent(state: NotebookUiState, vm: NotebookViewModel, modifier: Modifier) {
    val c = Nisaba.colors
    Column(
        modifier.padding(end = HANDLE_MARGIN.dp, bottom = (HANDLE_SIZE + 2 * HANDLE_MARGIN).dp).background(c.paper)
            .border(BorderStroke(1.dp, c.ink)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        com.nisaba.ui.theme.NisabaIconButton(
            com.nisaba.ui.theme.NisabaIcon.Pen, "Pen", { vm.setTool(com.nisaba.device.PenTool.Pen); vm.closeMiniBar() },
            selected = state.tool == com.nisaba.device.PenTool.Pen,
        )
        com.nisaba.ui.theme.NisabaIconButton(
            com.nisaba.ui.theme.NisabaIcon.Eraser, "Eraser", { vm.setTool(com.nisaba.device.PenTool.StrokeEraser); vm.closeMiniBar() },
            selected = state.tool.erases,
        )
        com.nisaba.ui.theme.NisabaIconButton(com.nisaba.ui.theme.NisabaIcon.Undo, "Undo", { vm.undo() }, enabled = state.canUndo)
        if (state.aiAvailable) {
            com.nisaba.ui.theme.NisabaIconButton(
                if (state.listening) com.nisaba.ui.theme.NisabaIcon.Listening else com.nisaba.ui.theme.NisabaIcon.Muted,
                "Listening", { vm.toggleListening(); vm.closeMiniBar() }, selected = state.listening,
            )
        }
    }
}

/** "Export as…": the system's save dialog for a PDF or a text file, then the notebook is written there. */
@Composable
internal fun rememberExportHandler(vm: NotebookViewModel): (NotebookEffect.Export) -> Unit {
    var asPdf by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    val pdf = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> vm.exportTo(true, uri?.toString()) }
    val text = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri -> vm.exportTo(false, uri?.toString()) }
    return { fx -> asPdf = fx.asPdf; if (fx.asPdf) pdf.launch(fx.name) else text.launch(fx.name) }
}
