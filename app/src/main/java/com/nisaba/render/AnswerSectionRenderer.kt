package com.nisaba.render

import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.nisaba.magic.Fonts
import com.nisaba.magic.MagicTheme
import com.nisaba.notebook.model.AnswerBlock
import com.nisaba.notebook.model.AnswerGroup

/**
 * Builds the visible answer section for one AnswerGroup (docs/08 §1–2):
 * flourish divider, blocks (cursive text in paragraphs, self-inking drawings,
 * ruled tables), status lines. When [reveal] is true the returned steps play
 * the block sequence through [InkReveal]; old answers render instantly.
 */
object AnswerSectionRenderer {

    class GroupView(val view: View, val revealSteps: List<InkReveal.Step>)

    fun buildGroupView(context: Context, group: AnswerGroup, reveal: Boolean = false): GroupView {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 14))
        }
        val steps = ArrayList<InkReveal.Step>()

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
        divider.visibility = View.GONE
        root.addView(divider)
        steps.add(InkReveal.Step(show = { divider.visibility = View.VISIBLE }))

        when (group.status) {
            "pending" -> root.addView(statusLine(context, "The ink stirs…"))
            "cancelled" -> root.addView(statusLine(context, "…as you were."))
            "error" -> {
                root.addView(statusLine(context, "The ink smudged — the spirit world was unreachable."))
                root.addView(statusLine(context, "(tap the quill to write it again)", small = true))
            }
            else -> {
                for (block in group.blocks) {
                    addBlock(root, steps, context, block, reveal)
                }
                if (group.blocks.isEmpty()) {
                    root.addView(statusLine(context, "«the answer smudged»"))
                }
            }
        }
        return GroupView(root, steps)
    }

    private fun addBlock(
        root: LinearLayout,
        steps: MutableList<InkReveal.Step>,
        context: Context,
        block: AnswerBlock,
        reveal: Boolean,
    ) {
        when (block.type) {
            "text" -> {
                val chunks = chunkParagraphs(block.markdown.orEmpty())
                for ((i, chunk) in chunks.withIndex()) {
                    val tv = TextView(context).apply {
                        text = MarkdownInk.render(chunk, context)
                        textSize = MarkdownInk.BODY_SP.toFloat()
                        setLineSpacing(0f, 1.35f)
                        movementMethod = LinkMovementMethod.getInstance()
                        setPadding(0, dp(context, 2), 0, dp(context, 6))
                        visibility = if (reveal && i > 0) View.GONE else View.VISIBLE
                    }
                    root.addView(tv)
                    if (reveal && i > 0) {
                        steps.add(InkReveal.Step(show = { tv.visibility = View.VISIBLE }))
                    }
                }
            }
            "drawing" -> {
                val items = SvgInkRenderer.parse(block.svg.orEmpty())
                if (items == null || items.isEmpty()) {
                    root.addView(placeholder(context, "«the drawing smudged»"))
                    return
                }
                val drawing = DrawingInkView(context).apply {
                    setDrawing(items)
                    visibility = if (reveal) View.GONE else View.VISIBLE
                }
                root.addView(drawing)
                if (reveal) {
                    steps.add(
                        InkReveal.Step(
                            show = {
                                drawing.visibility = View.VISIBLE
                                drawing.play()
                            },
                            cancel = { drawing.skipToEnd() },
                        )
                    )
                }
            }
            "table" -> {
                val (table, rowViews) = TableInkRenderer.build(context, block)
                table.visibility = if (reveal) View.GONE else View.VISIBLE
                root.addView(table)
                if (reveal) {
                    for (row in rowViews) {
                        row.visibility = View.GONE
                        steps.add(InkReveal.Step(show = { row.visibility = View.VISIBLE }))
                    }
                    steps.add(InkReveal.Step(show = { table.visibility = View.VISIBLE }))
                }
            }
            else -> root.addView(placeholder(context, ""))
        }
    }

    /** Splits markdown into paragraph chunks (each revealed as one step). */
    private fun chunkParagraphs(markdown: String): List<String> {
        val chunks = markdown.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return chunks.ifEmpty { listOf(markdown) }
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
