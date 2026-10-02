package com.nisaba.scribe.hand

/** The bundled hands, loaded from the module's resources exactly as the tools do. */
object HandFixtures {
    fun hand(name: String = "casual"): GlyphSet =
        GlyphSetParser.parse(HandFixtures::class.java.getResource("/hands/$name.json")!!.readText())
}
