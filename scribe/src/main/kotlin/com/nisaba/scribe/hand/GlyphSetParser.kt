package com.nisaba.scribe.hand

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable private class GlyphStrokeDto(val pts: List<Float>, val delayed: Boolean = false)

@Serializable private class GlyphDto(val adv: Float, val strokes: List<GlyphStrokeDto>)

@Serializable
private class GlyphSetDto(
    val name: String,
    val ascender: Float = 1.5f,
    val descender: Float = -0.5f,
    val capScale: Float = 1f,
    val capHeight: Float = 1.9f,
    val glyphs: Map<String, GlyphDto>,
    val math: Map<String, GlyphDto> = emptyMap(),
)

/** JSON produced by `tools/scripts/export_hershey.py` → [GlyphSet]. */
object GlyphSetParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): GlyphSet {
        val dto = json.decodeFromString<GlyphSetDto>(text)
        val glyphs = dto.glyphs.entries.filter { it.key.length == 1 }.associate { (k, g) ->
            k[0] to Glyph(g.adv, g.strokes.map { GlyphStroke(it.pts.toFloatArray(), it.delayed) })
        }
        val math = dto.math.mapValues { (_, g) -> Glyph(g.adv, g.strokes.map { GlyphStroke(it.pts.toFloatArray(), it.delayed) }) }
        return GlyphSet(dto.name, dto.ascender, dto.descender, glyphs, dto.capScale, dto.capHeight, math)
    }
}
