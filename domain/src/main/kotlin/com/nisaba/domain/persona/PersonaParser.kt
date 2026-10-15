package com.nisaba.domain.persona

private val TAG = Regex("<(name|tagline|voice|sample|hint|oops|length)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
private const val MAX_NAME = 40
private const val MAX_LINE = 90
private const val MAX_VOICE = 1500
private const val MIN_VOICE = 40
private const val BRIEF_WORDS = 35
private const val NORMAL_WORDS = 50
private const val LONG_WORDS = 80
private const val DEFAULT_HINT = "Write, then pause."

/**
 * The model's drafted voice for [description]: tags <name>, <tagline>, <voice>, <sample>, <hint>, <oops>, <length>.
 * Null when the reply has no usable name or voice. Every field is one line, trimmed and capped.
 */
fun parseVoiceDraft(reply: String, description: String): VoiceDraft? {
    val tags = TAG.findAll(reply).associate { it.groupValues[1] to oneLine(it.groupValues[2]) }
    val name = tags["name"]?.take(MAX_NAME)?.takeIf { it.isNotEmpty() } ?: return null
    val voice = tags["voice"]?.take(MAX_VOICE)?.takeIf { it.length >= MIN_VOICE } ?: return null
    val words = when (tags["length"]?.lowercase()) {
        "brief", "short" -> BRIEF_WORDS
        "long", "detailed" -> LONG_WORDS
        else -> NORMAL_WORDS
    }
    return VoiceDraft(
        description = description.trim(), name = name, tagline = tags["tagline"].orEmpty().take(MAX_LINE),
        voice = voice.replace("$", ""), sample = tags["sample"]?.takeIf { it.isNotEmpty() }?.take(MAX_LINE) ?: name,
        hint = tags["hint"]?.takeIf { it.isNotEmpty() }?.take(MAX_LINE) ?: DEFAULT_HINT,
        oops = tags["oops"].orEmpty().take(MAX_LINE), words = words,
    )
}

private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()
