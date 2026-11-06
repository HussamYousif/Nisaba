package com.nisaba.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nisaba.domain.conversation.Oracle
import com.nisaba.domain.conversation.OracleEvent
import com.nisaba.domain.conversation.OracleException
import com.nisaba.domain.conversation.OracleOptions
import com.nisaba.domain.conversation.OracleRequest
import com.nisaba.domain.conversation.failureMessage
import com.nisaba.domain.memory.ObserveMemoryUseCase
import com.nisaba.domain.settings.AnswerInk
import com.nisaba.domain.settings.CodeHighlight
import com.nisaba.domain.settings.HandSpeed
import com.nisaba.domain.settings.HandStyle
import com.nisaba.domain.settings.SecretStore
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.settings.SettingsStore
import com.nisaba.domain.usage.UsageSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Settings → About: this build in words ("0.1.0 debug · prompt v16") and the last crash kept on the device. */
class SettingsAbout(val build: String, val lastCrash: suspend () -> String?, val clearCrash: suspend () -> Unit)

/** Backups: now to a picked file, a restore from one, a folder for weekly ones (null in tests). */
class SettingsBackups(
    val backupTo: suspend (String) -> Boolean,
    val restoreFrom: suspend (String) -> Boolean,
    val setFolder: suspend (String) -> Unit,
)

class SettingsViewModel(
    private val store: SettingsStore,
    private val secrets: SecretStore,
    private val oracle: Oracle,
    memory: ObserveMemoryUseCase? = null,
    usage: (suspend () -> UsageSummary)? = null,
    scopeOverride: CoroutineScope? = null,
    private val backups: SettingsBackups? = null,
    private val about: SettingsAbout? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        scope.launch { store.observe().collect { s -> _state.update { it.copy(settings = s) } } }
        scope.launch { _state.update { it.copy(hasKey = !secrets.apiKey().isNullOrBlank()) } }
        memory?.let { m -> scope.launch { m().collect { facts -> _state.update { it.copy(factCount = facts.size) } } } }
        usage?.let { u -> scope.launch { val summary = u(); _state.update { it.copy(usage = summary) } } }
        about?.let { a -> scope.launch { val crash = a.lastCrash(); _state.update { it.copy(build = a.build, lastCrash = crash) } } }
    }

    fun clearCrash() {
        val a = about ?: return
        scope.launch { a.clearCrash(); _state.update { it.copy(lastCrash = null) } }
    }

    fun onKeyInput(text: String) = _state.update { it.copy(keyInput = text, keyCheck = KeyCheck.Idle) }

    fun saveKey() {
        val key = _state.value.keyInput.trim()
        scope.launch {
            secrets.setApiKey(key.ifEmpty { null })
            _state.update { it.copy(keyInput = "", hasKey = !secrets.apiKey().isNullOrBlank(), keyCheck = KeyCheck.Idle) }
        }
    }

    /** One tiny text-only request with the saved key; reports ✓ or the notebook's one-line error. */
    fun testKey() {
        _state.update { it.copy(keyCheck = KeyCheck.Testing) }
        scope.launch {
            val result = runCatching {
                var got = false
                oracle.ask(
                    OracleRequest(
                        "Reply with the single word OK.", null, emptyList(), "ping", emptyList(),
                        OracleOptions(_state.value.settings.model, thinking = false, maxTokens = 8),
                    ),
                ).collect { if (it is OracleEvent.Delta) got = true }
                got
            }
            _state.update {
                it.copy(
                    keyCheck = result.fold(
                        onSuccess = { ok -> if (ok) KeyCheck.Ok else KeyCheck.Failed("No answer") },
                        onFailure = { e ->
                            if (e is CancellationException) throw e
                            val error = (e as? OracleException)?.error
                            // here, "add one in Settings" would point at the very screen they are on
                            KeyCheck.Failed(
                                if (error == com.nisaba.domain.shared.DomainError.Auth) "DeepSeek refused this key"
                                else error?.let(::failureMessage) ?: "Something went wrong",
                            )
                        },
                    ),
                )
            }
        }
    }

    private fun update(transform: (Settings) -> Settings) { scope.launch { store.update(transform) } }

    fun setModel(model: String) = update { it.copy(model = model) }
    fun setThinking(on: Boolean) = update { it.copy(thinking = on) }
    fun setStandardPen(on: Boolean) = update { it.copy(standardPen = on) }
    fun setVoiceHands(on: Boolean) = update { it.copy(voiceHands = on) }
    fun setDailyPage(on: Boolean) = update { it.copy(dailyPage = on) }
    fun setPause(millis: Int) = update { it.copy(pauseMillis = millis.coerceIn(Settings.MIN_PAUSE, Settings.MAX_PAUSE)) }
    fun setSpeed(speed: HandSpeed) = update { it.copy(handSpeed = speed) }
    fun setStyle(style: HandStyle) = update { it.copy(handStyle = style) }
    fun setInk(ink: AnswerInk) = update { it.copy(answerInk = ink) }
    fun setCodeHighlight(style: CodeHighlight) = update { it.copy(codeHighlight = style) }
    fun finishOnboarding() = update { it.copy(onboarded = true) }

    fun backupTo(uri: String?) {
        val b = backups ?: return
        if (uri == null) return
        _state.update { it.copy(backupNote = "Backing up…") }
        scope.launch {
            val ok = b.backupTo(uri)
            _state.update { it.copy(backupNote = if (ok) "Backed up." else "The backup could not be written.") }
        }
    }

    fun setBackupFolder(uri: String?) {
        val b = backups ?: return
        if (uri == null) return
        scope.launch { b.setFolder(uri); _state.update { it.copy(backupNote = "Weekly backups go to that folder; one is there now.") } }
    }

    fun restoreFrom(uri: String?) {
        val b = backups ?: return
        if (uri == null) return
        _state.update { it.copy(backupNote = "Restoring…") }
        scope.launch {
            val ok = b.restoreFrom(uri)
            _state.update { if (ok) it.copy(restartNow = true) else it.copy(backupNote = NOT_A_BACKUP) }
        }
    }
}

private const val NOT_A_BACKUP = "That isn't a Nisaba backup; nothing was changed."
