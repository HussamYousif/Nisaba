package com.nisaba.domain.search

import com.nisaba.domain.memory.stripMemoryTags

private val TAG_RE = Regex("""<[^>]*>""")
private val FENCE_RE = Regex("""```[a-zA-Z]*""")
private val MARK_RE = Regex("""[*#$|`]""")
private val SPACE_RE = Regex("""\s+""")

/** The answer markup as one line of plain text: no tags, no markdown marks, no diagrams. */
fun plainText(markup: String): String {
    val answer = markup.substringAfter("<answer>", markup).substringBefore("</answer>")
    val noSvg = answer.replace(Regex("""<svg[\s\S]*?</svg>"""), " (a drawing) ")
    return stripMemoryTags(noSvg).replace(TAG_RE, " ").replace(FENCE_RE, " ").replace(MARK_RE, "").replace(SPACE_RE, " ").trim()
}

/** About [width] characters of [text] around the first [query] match, with "…" where it was cut. */
fun snippet(text: String, query: String, width: Int = 140): String {
    if (text.length <= width) return text
    val at = text.indexOf(query.trim(), ignoreCase = true).coerceAtLeast(0)
    val start = (at - width / 3).coerceIn(0, text.length - width)
    val end = start + width
    return (if (start > 0) "…" else "") + text.substring(start, end).trim() + (if (end < text.length) "…" else "")
}
