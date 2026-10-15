package com.nisaba.notebook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
internal enum class StarBadge { Working, Failed, Muted }

/** The small Nisaba star in the corner while the bar is hidden: tap it to bring the bar back. Its badge is one glance. */
@Composable
internal fun ToolbarHandle(modifier: Modifier, badge: StarBadge?, onTap: () -> Unit) {
    val c = Nisaba.colors
    Box(modifier.padding(HANDLE_MARGIN.dp).size(HANDLE_SIZE.dp).clickable(onClick = onTap)) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.nisaba.R.drawable.ic_mark), "Show the toolbar", Modifier.fillMaxSize(),
        )
        val corner = Modifier.align(Alignment.TopStart).size(BADGE.dp).background(c.paper)
        when (badge) {
            StarBadge.Working -> Text("…", corner, style = MaterialTheme.typography.labelLarge, color = c.lapis)
            StarBadge.Failed -> Text("?", corner, style = MaterialTheme.typography.labelLarge, color = c.ink)
            StarBadge.Muted -> com.nisaba.ui.theme.NisabaIconGlyph(com.nisaba.ui.theme.NisabaIcon.Muted, c.ink, corner)
            null -> Unit
        }
    }
}

private const val BADGE = 18

const val HANDLE_SIZE = 44
const val HANDLE_MARGIN = 16
