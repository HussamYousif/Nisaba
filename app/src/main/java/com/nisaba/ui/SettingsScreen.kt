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
import androidx.appcompat.widget.SwitchCompat
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
    private lateinit var tempField: EditText
    private lateinit var tokensField: EditText
    private lateinit var autoSwitch: SwitchCompat
    private lateinit var selectedHint: TextView
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
            text = context.getString(R.string.settings_back)
            textSize = 26f
            setTextColor(MagicTheme.INK)
            setPadding(0, 0, dp(16), 0)
            setOnClickListener {
                // A TextView is not focusable in touch mode: tapping it never
                // moves focus off the EditTexts, so without an explicit flush
                // a freshly typed key would be discarded (audit P0-8).
                persist()
                this@SettingsScreen.onBack?.invoke()
            }
        })
        header.addView(TextView(context).apply {
            text = context.getString(R.string.settings_title)
            typeface = Fonts.dancingScript
            textSize = 30f
            setTextColor(MagicTheme.INK)
        })
        root.addView(header)

        // API key
        root.addView(label(context, context.getString(R.string.settings_api_key)))
        keyField = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(settings.apiKey)
            setTextColor(MagicTheme.INK)
            hint = "sk-…"
        }
        root.addView(keyField)

        // Base URL
        root.addView(label(context, context.getString(R.string.settings_base_url)))
        baseField = EditText(context).apply {
            setText(settings.baseUrl)
            setTextColor(MagicTheme.INK)
        }
        root.addView(baseField)

        // Model
        root.addView(label(context, context.getString(R.string.settings_model)))
        modelField = EditText(context).apply {
            setText(settings.model)
            setTextColor(MagicTheme.INK)
        }
        root.addView(modelField)

        // Auto-summon
        autoSwitch = SwitchCompat(context).apply {
            isChecked = settings.autoSummonEnabled
            text = context.getString(R.string.settings_auto_summon)
            setTextColor(MagicTheme.INK)
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(autoSwitch)

        // Idle seconds
        root.addView(label(context, context.getString(R.string.settings_idle_seconds)))
        idleField = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.idlePauseSeconds.toString())
            setTextColor(MagicTheme.INK)
        }
        root.addView(idleField)

        // Temperature (F7.3, M5)
        root.addView(label(context, context.getString(R.string.settings_temperature)))
        tempField = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(settings.temperature.toString())
            setTextColor(MagicTheme.INK)
        }
        root.addView(tempField)

        // Max tokens (F7.3, M5)
        root.addView(label(context, context.getString(R.string.settings_max_tokens)))
        tokensField = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.maxTokens.toString())
            setTextColor(MagicTheme.INK)
        }
        root.addView(tokensField)

        // Ink color (F2.5, M5): the palette from docs/04 §6.
        root.addView(label(context, context.getString(R.string.settings_ink_colour)))
        val inkRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        fun selectSwatch(chosen: TextView) {
            for (i in 0 until inkRow.childCount) inkRow.getChildAt(i).background = null
            chosen.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x22FFFFFF.toInt())
                cornerRadius = 6f
            }
        }
        for ((key, color) in listOf(
            "ink" to MagicTheme.INK,
            "sepia" to MagicTheme.INK_SOFT,
            "blue" to MagicTheme.BLUE_INK,
        )) {
            inkRow.addView(TextView(context).apply {
                text = "●"
                textSize = 26f
                setTextColor(color)
                setPadding(dp(10), 0, dp(10), 0)
                setOnClickListener {
                    settings.inkColor = key
                    selectSwatch(this)
                    selectedHint.text = context.getString(R.string.settings_ink_selected, key)
                }
                if (settings.inkColor == key) selectSwatch(this)
            })
        }
        root.addView(inkRow)
        selectedHint = TextView(context).apply {
            textSize = 12f
            typeface = Fonts.kalam
            setTextColor(MagicTheme.INK_SOFT)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(selectedHint)

        // Test connection
        root.addView(TextView(context).apply {
            text = context.getString(R.string.settings_test_connection)
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

        // Dev-key hint: a key from .env makes summoning work without typing.
        if (settings.apiKey.isBlank() && settings.devApiKey.isNotBlank()) {
            root.addView(TextView(context).apply {
                text = context.getString(R.string.settings_dev_key_hint)
                typeface = Fonts.kalam
                textSize = 13f
                setTextColor(MagicTheme.ACCENT_RED)
                setPadding(0, dp(6), 0, 0)
            })
        }

        // Persist on change (paper-feel: no save button).
        keyField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) persist() }
        baseField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) persist() }
        modelField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) persist() }
        idleField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) persist() }
        tempField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) persist() }
        tokensField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) persist() }
        autoSwitch.setOnCheckedChangeListener { _, checked -> settings.autoSummonEnabled = checked }
    }

    /** Flushes the editable fields into settings (audit P0-8). */
    private fun persist() {
        val s = settings ?: return
        s.apiKey = keyField.text.toString()
        s.baseUrl = baseField.text.toString()
        s.model = modelField.text.toString()
        s.idlePauseSeconds = idleField.text.toString().toIntOrNull() ?: 3
        s.temperature = tempField.text.toString().toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.7f
        s.maxTokens = tokensField.text.toString().toIntOrNull()?.coerceIn(256, 16384) ?: 4096
    }

    private fun testConnection() {
        val s = settings ?: return
        persist()
        if (s.effectiveApiKey().isBlank()) {
            testResult.text = context.getString(R.string.settings_no_key)
            return
        }
        testResult.text = context.getString(R.string.settings_stirring)
        scope.launch {
            // Explicit try/catch, not runCatching: runCatching swallows the
            // CancellationException from onClose(), and the coroutine then
            // writes to a detached view (audit P1-11).
            var error: Exception? = null
            val reply = try {
                DeepSeekClient(s).chat(
                    ChatContract.ChatRequest(
                        model = s.model,
                        // json_object replies need more than a handful of
                        // tokens (a bare "ping" with max_tokens=8 came back
                        // empty).
                        max_tokens = 64,
                        messages = listOf(
                            // response_format=json_object requires the word
                            // "json" in the prompt (the API rejects "ping").
                            ChatContract.textMessage("user", "Reply with a JSON object like {\"ok\": true}")
                        ),
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
                null
            }
            testResult.text = if (reply != null) {
                context.getString(R.string.settings_scribes_answer, reply.take(60))
            } else {
                context.getString(R.string.settings_no_answer, error?.message?.take(80).orEmpty())
            }
        }
    }

    fun onClose() {
        // The screen is leaving without a focus change (system back, app
        // close): flush so a freshly typed key survives (audit P0-8).
        persist()
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
