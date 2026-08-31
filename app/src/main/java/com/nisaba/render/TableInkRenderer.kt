package com.nisaba.render

import android.content.Context
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.nisaba.magic.Fonts
import com.nisaba.magic.MagicTheme
import com.nisaba.notebook.model.AnswerBlock
import kotlin.math.max

/**
 * Ruled ledger table (docs/08 §6): Great Vibes header with a double rule
 * under it, Kalam cells, vertical rules between columns, horizontal rules
 * every four rows. Rows are revealed one by one (the caller drives it).
 */
object TableInkRenderer {

    private const val MAX_CELL_WIDTH_DP = 220f
    private const val RULE_EVERY_ROWS = 4

    /** Builds the table view; returns (view, rowViews) so the reveal can show rows in order. */
    fun build(context: Context, block: AnswerBlock): Pair<View, List<View>> {
        val density = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val headers = block.headers.orEmpty()
        val rows = block.rows.orEmpty()
        val columnCount = max(headers.size, rows.map { it.size }.maxOrNull() ?: 0).coerceAtLeast(1)
        val columnWeights = columnWeights(context, headers, rows, columnCount)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), dp(6f), dp(4f), dp(8f))
        }

        block.title?.takeIf { it.isNotBlank() }?.let { title ->
            root.addView(TextView(context).apply {
                text = title
                typeface = Fonts.greatVibes
                textSize = 22f
                setTextColor(MagicTheme.INK)
                setPadding(0, 0, 0, dp(4f))
            })
        }

        // Header row + double rule beneath it.
        val headerRow = row(context, headers, columnCount, columnWeights, header = true)
        root.addView(headerRow)
        root.addView(rule(context))
        root.addView(rule(context, thicker = true))
        root.addView(space(context, dp(4f)))

        // Body rows with a horizontal rule every RULE_EVERY_ROWS rows.
        val rowViews = ArrayList<View>(rows.size)
        for ((i, cells) in rows.withIndex()) {
            val r = row(context, cells, columnCount, columnWeights, header = false)
            root.addView(r)
            rowViews.add(r)
            if (i > 0 && (i + 1) % RULE_EVERY_ROWS == 0) {
                root.addView(rule(context))
            }
        }
        return root to rowViews
    }

    private fun columnWeights(
        context: Context,
        headers: List<String>,
        rows: List<List<String>>,
        count: Int,
    ): FloatArray {
        val density = context.resources.displayMetrics.density
        val maxPx = MAX_CELL_WIDTH_DP * density
        val measure = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 15f * context.resources.displayMetrics.scaledDensity
        }
        val widths = FloatArray(count) { 0f }
        headers.forEachIndexed { i, h -> if (i < count) widths[i] = max(widths[i], measure.measureText(h)) }
        for (row in rows) {
            row.forEachIndexed { i, c -> if (i < count) widths[i] = max(widths[i], measure.measureText(c)) }
        }
        // Clamp + normalize to weights.
        var total = 0f
        val weights = FloatArray(count)
        for (i in 0 until count) {
            weights[i] = widths[i].coerceAtMost(maxPx) + 24f * density
            total += weights[i]
        }
        for (i in 0 until count) weights[i] = weights[i] / total
        return weights
    }

    private fun row(
        context: Context,
        cells: List<String>,
        count: Int,
        weights: FloatArray,
        header: Boolean,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (i in 0 until count) {
            val text = cells.getOrNull(i).orEmpty()
            row.addView(
                TextView(context).apply {
                    this.text = text
                    typeface = if (header) Fonts.greatVibes else Fonts.kalam
                    textSize = if (header) 16f else 15f
                    setTextColor(MagicTheme.INK)
                    setPadding(dp8(context, 8), dp8(context, 2), dp8(context, 8), dp8(context, 2))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[i])
                }
            )
            if (i < count - 1) {
                row.addView(
                    View(context).apply {
                        setBackgroundColor(MagicTheme.INK_SOFT)
                        layoutParams = LinearLayout.LayoutParams(dp8(context, 1), LinearLayout.LayoutParams.MATCH_PARENT)
                    }
                )
            }
        }
        return row
    }

    private fun rule(context: Context, thicker: Boolean = false): View =
        View(context).apply {
            setBackgroundColor(if (thicker) MagicTheme.INK else MagicTheme.INK_SOFT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp8(context, if (thicker) 2 else 1),
            )
        }

    private fun space(context: Context, h: Int): View =
        View(context).apply { layoutParams = LinearLayout.LayoutParams(1, h) }

    private fun dp8(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
