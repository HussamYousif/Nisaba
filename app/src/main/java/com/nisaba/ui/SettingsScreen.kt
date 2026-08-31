package com.nisaba.ui

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.nisaba.R
import com.nisaba.llm.AppSettings
import com.nisaba.llm.ChatContract
import com.nisaba.llm.DeepSeekClient
import com.nisaba.magic.Fonts
import com.nisaba.magic.MagicTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Settings (docs/02 F7): API key (encrypted), base URL, model, summon
 * behaviour, and a live connection test. Values apply immediately.
 */
class SettingsScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    private var settings: AppSettings? = null
    private var onBack: (() -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var keyField: EditText
    private lateinit var baseField: EditText
    private lateinit var modelField: EditText
    private lateinit var idleField: EditText
    private lateinit var autoSwitch: Switch
    private lateinit var testResult: TextView

    fun bind(settings: AppSettings, onBack: () -> Unit) {
        this.settings = settings
        this.onBack = onBack

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(24))
        }
        addView(root)

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        header.addView(TextView(context).apply {
            text = "←"
            textSize = 26f
            setTextColor(MagicTheme.INK)
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { this@SettingsScreen.onBack?.invoke() }
        })
        header.addView(TextView(context).apply {
            text = "Settings"
            typeface = Fonts.dancingScript
            textSize = 30f
            setTextColor(MagicTheme.INK)
        })
        root.addView(header)

        // API key
        root.addView(label(context, "DeepSeek API key"))
        keyField = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(settings.apiKey)
            setTextColor(MagicTheme.INK)
            hint = "sk-…"
        }
        root.addView(keyField)

        // Base URL
        root.addView(label(context, "Base URL"))
        baseField = EditText(context).apply {
            setText(settings.baseUrl)
            setTextColor(MagicTheme.INK)
        }
        root.addView(baseField)

        // Model
        root.addView(label(context, "Model"))
        modelField = EditText(context).apply {
            setText(settings.model)
            setTextColor(MagicTheme.INK)
        }
        root.addView(modelField)

        // Auto-summon
        autoSwitch = Switch(context).apply {
            isChecked = settings.autoSummonEnabled
            text = "Answer when I pause (auto-summon)"
            setTextColor(MagicTheme.INK)
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(autoSwitch)

        // Idle seconds
        root.addView(label(context, "Pause before answering (seconds)"))
        idleField = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.idlePauseSeconds.toString())
            setTextColor(MagicTheme.INK)
        }
        root.addView(idleField)

        // Test connection
        root.addView(TextView(context).apply {
            text = "🖋 Test the connection"
            typeface = Fonts.kalamBold
            textSize = 16f
            setTextColor(MagicTheme.ACCENT_RED)
            setPadding(0, dp(16), 0, dp(4))
            setOnClickListener { testConnection() }
        })
        testResult = TextView(context).apply {
            textSize = 14f
            typeface = Fonts.kalam
            setTextColor(MagicTheme.INK_SOFT)
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(testResult)

        // Persist on change (paper-feel: no save button).
        keyField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) settings.apiKey = keyField.text.toString() }
        baseField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) settings.baseUrl = baseField.text.toString() }
        modelField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) settings.model = modelField.text.toString() }
        idleField.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) settings.idlePauseSeconds = idleField.text.toString().toIntOrNull() ?: 3
        }
        autoSwitch.setOnCheckedChangeListener { _, checked -> settings.autoSummonEnabled = checked }
    }

    private fun testConnection() {
        val s = settings ?: return
        s.apiKey = keyField.text.toString()
        s.baseUrl = baseField.text.toString()
        s.model = modelField.text.toString()
        if (s.apiKey.isBlank()) {
            testResult.text = "The quill has no ink — enter your API key first."
            return
        }
        testResult.text = "The ink stirs…"
        scope.launch {
            val result = runCatching {
                DeepSeekClient(s).chat(
                    ChatContract.ChatRequest(
                        model = s.model,
                        max_tokens = 8,
                        messages = listOf(
                            ChatContract.textMessage("user", "ping")
                        ),
                    )
                )
            }
            testResult.text = if (result.isSuccess) {
                "The scribe answers: \"${result.getOrNull().orEmpty().take(60)}\""
            } else {
                "No answer — ${result.exceptionOrNull()?.message?.take(80)}"
            }
        }
    }

    fun onClose() {
        scope.cancel()
    }

    private fun label(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            typeface = Fonts.kalam
            textSize = 13f
            setTextColor(MagicTheme.INK_SOFT)
            setPadding(0, dp(10), 0, dp(2))
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
