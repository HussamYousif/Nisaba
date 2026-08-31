package com.nisaba.render

import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.nisaba.magic.Fonts
import com.nisaba.magic.MagicTheme
import com.nisaba.notebook.model.AnswerGroup

/**
 * Builds the visible answer section for one AnswerGroup (docs/08 §1–2):
 * flourish divider, blocks (text rendered now; drawing/table placeholders
 * until M4's ink renderers land), and the status line.
 */
object AnswerSectionRenderer {

    fun buildGroupView(context: Context, group: AnswerGroup): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 14))
        }

        // ── ✦ divider ──
        val divider = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 4), 0, dp(context, 10))
        }
        divider.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 1), 1f)
            setBackgroundColor(MagicTheme.INK_SOFT)
        })
        divider.addView(TextView(context).apply {
            text = "✦"
            typeface = Fonts.greatVibes
            textSize = 16f
            setTextColor(MagicTheme.ACCENT_RED)
            setPadding(dp(context, 10), 0, dp(context, 10), 0)
        })
        divider.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 1), 1f)
            setBackgroundColor(MagicTheme.INK_SOFT)
        })
        root.addView(divider)

        when (group.status) {
            "pending" -> root.addView(statusLine(context, "The ink stirs…"))
            "cancelled" -> root.addView(statusLine(context, "…as you were."))
            "error" -> {
                root.addView(statusLine(context, "The ink smudged — the spirit world was unreachable."))
                root.addView(statusLine(context, "(tap the quill to write it again)", small = true))
            }
            else -> {
                for (block in group.blocks) {
                    root.addView(blockView(context, block))
                }
                if (group.blocks.isEmpty()) {
                    root.addView(statusLine(context, "«the answer smudged»"))
                }
            }
        }
        return root
    }

    private fun blockView(context: Context, block: com.nisaba.notebook.model.AnswerBlock): View {
        return when (block.type) {
            "text" -> TextView(context).apply {
                text = MarkdownInk.render(block.markdown.orEmpty(), context)
                textSize = MarkdownInk.BODY_SP.toFloat()
                setLineSpacing(0f, 1.35f)
                movementMethod = LinkMovementMethod.getInstance()
                setPadding(0, dp(context, 2), 0, dp(context, 6))
            }
            "drawing" -> placeholder(context, "✧ a drawing will be inked here (M4) ✧")
            "table" -> placeholder(context, "❧ a table will be ruled here (M4) ❧")
            else -> placeholder(context, "")
        }
    }

    private fun placeholder(context: Context, text: String): View =
        TextView(context).apply {
            this.text = text
            typeface = Fonts.kalam
            textSize = 14f
            setTextColor(MagicTheme.INK_SOFT)
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 6), 0, dp(context, 6))
        }

    private fun statusLine(context: Context, text: String, small: Boolean = false): View =
        TextView(context).apply {
            this.text = text
            typeface = Fonts.kalam
            textSize = if (small) 12f else 15f
            setTextColor(if (small) MagicTheme.INK_SOFT else MagicTheme.INK)
            setPadding(0, dp(context, 2), 0, dp(context, 2))
        }

    private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
