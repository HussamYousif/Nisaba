package com.nisaba.render

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import com.nisaba.magic.Fonts
import com.nisaba.magic.MagicTheme

/**
 * Renders the model's markdown subset (docs/08 §3): ## headings, **bold**,
 * *italics*, - bullets, 1. numbered lists, paragraphs — in the cursive
 * typography (Dancing Script body, Great Vibes headings).
 */
object MarkdownInk {

    const val BODY_SP = 20
    const val HEADING_SP = 26

    private val INLINE = Regex("""(\*\*[^*]+\*\*|\*[^*]+\*)""")

    fun render(markdown: String, context: Context): SpannableStringBuilder {
        val density = context.resources.displayMetrics.scaledDensity
        val out = SpannableStringBuilder()
        val lines = markdown.replace("\r\n", "\n").split("\n")
        for ((i, rawLine) in lines.withIndex()) {
            val line = rawLine.trimEnd()
            when {
                line.isBlank() -> {
                    // paragraph gap
                }
                line.startsWith("## ") -> {
                    appendStyled(out, line.removePrefix("## ").trim(), Fonts.greatVibes, HEADING_SP, density)
                }
                line.startsWith("- ") -> {
                    appendStyled(out, "•  " + line.removePrefix("- ").trim(), Fonts.dancingScript, BODY_SP, density)
                }
                line.matches(Regex("""\d+[.)] .*""")) -> {
                    val (num, content) = if (line.contains('.')) {
                        line.substringBefore('.') to line.substringAfter(". ")
                    } else {
                        line.substringBefore(')') to line.substringAfter(") ")
                    }
                    appendStyled(out, "$num.  $content", Fonts.dancingScript, BODY_SP, density)
                }
                else -> {
                    appendStyled(out, line, Fonts.dancingScript, BODY_SP, density)
                }
            }
            if (i < lines.lastIndex) out.append("\n")
        }
        return out
    }

    private fun typefaceSpan(face: Typeface): TypefaceSpan =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            TypefaceSpan(face) // custom typeface spans need API 28+
        } else {
            TypefaceSpan("sans") // API 26–27 fallback (Boox runs 13+)
        }

    private fun appendStyled(
        out: SpannableStringBuilder,
        text: String,
        typeface: Typeface,
        sp: Int,
        density: Float,
    ) {
        val px = (sp * density).toInt()
        val start = out.length
        // Split on **bold** / *italic* runs; anything else keeps the base face.
        val parts = INLINE.split(text)
        var offset = 0
        for ((idx, part) in parts.withIndex()) {
            if (part.isEmpty()) continue
            out.append(part)
            val s = start + offset
            val e = s + part.length
            val isBold = idx % 2 == 1 && part.startsWith("**")
            val isItalic = idx % 2 == 1 && part.startsWith("*") && !isBold
            val face = when {
                isBold -> Fonts.dancingScriptBold
                else -> typeface
            }
            out.setSpan(typefaceSpan(face), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(AbsoluteSizeSpan(px), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(MagicTheme.INK), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (isItalic) {
                out.setSpan(StyleSpan(Typeface.ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            offset += part.length
        }
        out.append(" ")
    }
}
