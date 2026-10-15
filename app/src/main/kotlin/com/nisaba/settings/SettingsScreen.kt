package com.nisaba.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.nisaba.canvas.answerPen
import com.nisaba.domain.settings.CodeHighlight
import com.nisaba.scribe.layout.InkTone
import com.nisaba.domain.answer.Block
import com.nisaba.ui.brand.HandInkComponent
import com.nisaba.ui.brand.HandTextComponent
import com.nisaba.ui.brand.LocalHandText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nisaba.domain.settings.AnswerInk
import com.nisaba.domain.settings.HandSpeed
import com.nisaba.domain.settings.HandStyle
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.usage.UsageTotals
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaDialog
import com.nisaba.ui.theme.NisabaTextField

private const val PAUSE_STEP = 500

private enum class Pane(val title: String) {
    Key("Key & model"), Waiting("Waiting"), Hand("The hand"), Ink("Ink & colours"), Pen("Your pen"), Memory("Memory"), Backup("Backup"),
    Usage("Usage"),
}

/** Settings as short panes (no scrolling on e-ink): the list on the left, one pane on the right. */
@Composable
fun SettingsScreen(state: SettingsUiState, vm: SettingsViewModel, onBack: () -> Unit, onOpenMemory: () -> Unit = {}) {
    val c = Nisaba.colors
    var pane by remember { mutableStateOf(Pane.Key) }
    Column(Modifier.fillMaxSize().background(c.paper).padding(32.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            NisabaButton("Done", onBack, filled = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Column(Modifier.width(PANE_LIST_WIDTH.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (p in Pane.entries) {
                    if (p == Pane.Usage && state.usage == null) continue
                    NisabaButton(p.title, { pane = p }, Modifier.fillMaxWidth(), filled = p == pane)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(pane.title, style = MaterialTheme.typography.titleLarge)
                when (pane) {
                    Pane.Key -> KeyPane(state, vm)
                    Pane.Waiting -> WaitingPane(state.settings, vm)
                    Pane.Hand -> HandPane(state.settings, vm)
                    Pane.Ink -> InkPane(state.settings, vm)
                    Pane.Pen -> PenPane(state.settings, vm)
                    Pane.Memory -> MemoryPane(state.factCount, onOpenMemory)
                    Pane.Backup -> BackupPane(state, vm)
                    Pane.Usage -> state.usage?.let { UsagePane(it) }
                }
            }
        }
    }
}

private const val PANE_LIST_WIDTH = 260

@Composable
private fun KeyPane(state: SettingsUiState, vm: SettingsViewModel) {
    val s = state.settings
    val note = if (state.hasKey) "A DeepSeek key is saved on this device." else "No key yet. Paste one to let Nisaba answer."
    Text(note, style = MaterialTheme.typography.bodyLarge)
    NisabaTextField(state.keyInput, vm::onKeyInput, onDone = vm::saveKey, autoFocus = false)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        NisabaButton("Save key", vm::saveKey, enabled = state.keyInput.isNotBlank())
        NisabaButton("Test key", vm::testKey, enabled = state.hasKey && state.keyCheck != KeyCheck.Testing)
        Text(
            when (val k = state.keyCheck) {
                KeyCheck.Idle -> ""
                KeyCheck.Testing -> "Asking…"
                KeyCheck.Ok -> "✓ the ink flows"
                is KeyCheck.Failed -> "✗ ${k.message}"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    Spacer(Modifier.height(12.dp))
    Choice(listOf("deepseek-flash" to "Flash (fast)", "deepseek-v4-pro" to "Pro (slower, deeper)"), s.model, vm::setModel)
    Toggle("Think before answering (slower)", s.thinking, vm::setThinking)
}

@Composable
private fun WaitingPane(s: Settings, vm: SettingsViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        NisabaButton("−", { vm.setPause(s.pauseMillis - PAUSE_STEP) }, enabled = s.pauseMillis > Settings.MIN_PAUSE)
        Text("%.1f seconds of quiet before it answers".format(s.pauseMillis / 1000f), style = MaterialTheme.typography.bodyLarge)
        NisabaButton("+", { vm.setPause(s.pauseMillis + PAUSE_STEP) }, enabled = s.pauseMillis < Settings.MAX_PAUSE)
    }
    Text("Mornings", style = MaterialTheme.typography.labelLarge)
    Choice(listOf(false to "Just open", true to "Start a page each day"), s.dailyPage, vm::setDailyPage)
    Text(
        "The first time a notebook opens on a new day, Nisaba writes the date, what was left open, and a question.",
        style = MaterialTheme.typography.labelSmall, color = Nisaba.colors.faint,
    )
}

@Composable
private fun PenPane(s: Settings, vm: SettingsViewModel) {
    Choice(listOf(false to "Boox pen (fastest)", true to "Standard pen"), s.standardPen, vm::setStandardPen)
    Text(
        "Use the standard pen if writing ever stops working after a Boox update. It applies the next time a notebook opens.",
        style = MaterialTheme.typography.bodyLarge, color = Nisaba.colors.faint,
    )
}

@Composable
private fun HandPane(s: Settings, vm: SettingsViewModel) {
    Text("Speed", style = MaterialTheme.typography.labelLarge)
    Choice(HandSpeed.entries.map { it to it.name }, s.handSpeed, vm::setSpeed)
    Text("Style", style = MaterialTheme.typography.labelLarge)
    Choice(listOf(HandStyle.Casual to "Joined (clear)", HandStyle.Formal to "Full cursive (pretty)"), s.handStyle, vm::setStyle)
    Text("Voices", style = MaterialTheme.typography.labelLarge)
    Choice(listOf(true to "Each its own hand", false to "All alike"), s.voiceHands, vm::setVoiceHands)
    Text(
        "Each voice writes in its own ink, slant and neatness; a swift voice writes faster while Speed is Natural.",
        style = MaterialTheme.typography.labelSmall, color = Nisaba.colors.faint,
    )
}

private val PREVIEW_CODE = listOf("def greet(name):  # say hi", "    return \"Hi \" + name")

/** The answer ink and how code is marked, with a preview written by the hand in exactly those inks. */
@Composable
private fun InkPane(s: Settings, vm: SettingsViewModel) {
    val c = Nisaba.colors
    Text("Answer ink", style = MaterialTheme.typography.labelLarge)
    Choice(listOf(AnswerInk.Lapis to "Lapis blue", AnswerInk.Black to "Black"), s.answerInk, vm::setInk)
    Text("Code", style = MaterialTheme.typography.labelLarge)
    Choice(
        listOf(CodeHighlight.Colour to "Coloured pens", CodeHighlight.Weight to "Bold & thin", CodeHighlight.Off to "Plain"),
        s.codeHighlight, vm::setCodeHighlight,
    )
    Column(
        Modifier.fillMaxWidth().border(BorderStroke(1.dp, c.faint)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HandTextComponent("Lima, naturally.", Color(answerPen(InkTone.Plain, s.answerInk).color.argb), Modifier.height(PREVIEW_LINE.dp))
        val cache = LocalHandText.current
        val code = remember(cache) { cache?.of(Block.Code("python", PREVIEW_CODE), lineWidth = PREVIEW_CODE_WIDTH) }
        if (code != null) {
            HandInkComponent(
                code, { tone -> answerPen(tone, s.answerInk, s.codeHighlight).let { Color(it.color.argb) to it.widthScale } },
                Modifier.fillMaxWidth(),
            )
        }
    }
    Text(
        "Colours look softer on the Kaleido screen than here; pick what reads well on yours.",
        style = MaterialTheme.typography.labelSmall, color = c.faint,
    )
}

private const val PREVIEW_LINE = 30
private const val PREVIEW_CODE_WIDTH = 1300f // page units: wide enough that the sample lines never wrap

@Composable
private fun MemoryPane(factCount: Int, onOpenMemory: () -> Unit) {
    val what = when (factCount) { 0 -> "nothing"; 1 -> "1 thing"; else -> "$factCount things" }
    Text("Nisaba remembers $what about you, across all notebooks.", style = MaterialTheme.typography.bodyLarge)
    NisabaButton("View", onOpenMemory)
}

@Composable
private fun UsagePane(u: com.nisaba.domain.usage.UsageSummary) {
    Text("This month: ${describe(u.thisMonth)}", style = MaterialTheme.typography.bodyLarge)
    Text("All time: ${describe(u.allTime)}", style = MaterialTheme.typography.bodyLarge)
    val body = MaterialTheme.typography.bodyLarge
    u.firstInkMedianMillis?.let {
        Text("First ink about %.1f s after the pause (typical of the last answers)".format(it / MS), style = body)
    }
    u.silentShare?.let { Text("${(it * PERCENT).toInt()}% of this month's pauses needed no answer", style = body) }
    Text(
        "Cost is estimated from DeepSeek's list prices (peak hours cost double).",
        style = MaterialTheme.typography.labelSmall, color = Nisaba.colors.faint,
    )
}

/** "12 answers · 45.2k tokens in (30.1k cached) · 2.3k out · ≈ $0.01" */
private fun describe(t: UsageTotals): String {
    if (t.answers == 0) return "nothing yet"
    val cost = t.costUsd?.let { " · ≈ $" + if (it < 0.01) "%.4f".format(it) else "%.2f".format(it) }.orEmpty()
    val answers = if (t.answers == 1) "1 answer" else "${t.answers} answers"
    return "$answers · ${tokens(t.inputTokens)} tokens in (${tokens(t.cachedTokens)} cached) · ${tokens(t.outputTokens)} out$cost"
}

private fun tokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((value, label) in options) NisabaButton(label, { onSelect(value) }, filled = value == selected)
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        NisabaButton(if (on) "On" else "Off", { onChange(!on) }, filled = on)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private const val MS = 1000f
private const val PERCENT = 100

/**
 * Backups: a zip of every notebook, its ink and PDFs, and the settings (not the key). Now to a file, every week to a
 * folder, or back from one (it replaces everything here; the app starts again).
 */
@Composable
private fun BackupPane(state: SettingsUiState, vm: SettingsViewModel) {
    val c = Nisaba.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val now = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { vm.backupTo(it?.toString()) }
    val folder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { vm.setBackupFolder(it?.toString()) }
    val restore = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { vm.restoreFrom(it?.toString()) }
    androidx.compose.runtime.LaunchedEffect(state.restartNow) { if (state.restartNow) restartApp(context) }
    val s = state.settings
    val last = if (s.lastBackupMillis == 0L) "No backup yet." else {
        "Last backup: " + com.nisaba.library.ago(s.lastBackupMillis, System.currentTimeMillis())
    }
    Text(last, style = MaterialTheme.typography.bodyLarge)
    NisabaButton("Back up now…", { now.launch("nisaba-" + java.time.LocalDate.now() + ".zip") })
    val weekly = if (s.backupFolder.isBlank()) "Every week, to a folder…" else "Weekly backups: change the folder…"
    NisabaButton(weekly, { folder.launch(null) })
    NisabaButton("Restore from a backup…", { confirm = true })
    state.backupNote?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = c.faint) }
    Text(
        "Your DeepSeek key stays out of backups: add it again after restoring on a new device.",
        style = MaterialTheme.typography.labelSmall, color = c.faint,
    )
    if (confirm) {
        NisabaDialog("Restore from a backup?", { confirm = false }) {
            Text(
                "Every notebook on this device is replaced by the backup's. Nisaba starts again afterwards.",
                style = MaterialTheme.typography.bodyLarge,
            )
            com.nisaba.ui.theme.NisabaButtonRow {
                NisabaButton("Cancel", { confirm = false })
                val pick = { confirm = false; restore.launch(arrayOf("application/zip", "application/octet-stream")) }
                NisabaButton("Choose the backup", pick, filled = true)
            }
        }
    }
}

/** After a restore: start afresh (the open database and every cache belong to what was replaced). */
private fun restartApp(context: android.content.Context) {
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
    Runtime.getRuntime().exit(0)
}
