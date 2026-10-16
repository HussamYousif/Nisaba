package com.nisaba.app

import com.nisaba.domain.settings.HandStyle
import com.nisaba.domain.shared.Logger
import com.nisaba.scribe.hand.GlyphSet
import com.nisaba.scribe.hand.GlyphSetParser

/** The bundled stroke fonts (module resources of :scribe), parsed once per style. */
class HandRegistry(private val logger: Logger) {
    private val hands = HashMap<HandStyle, GlyphSet>()

    fun hand(style: HandStyle): GlyphSet = synchronized(hands) {
        hands.getOrPut(style) {
            val name = if (style == HandStyle.Formal) "formal" else "casual"
            val t0 = android.os.SystemClock.uptimeMillis()
            val text = GlyphSet::class.java.classLoader!!.getResourceAsStream("hands/$name.json")!!.bufferedReader().use { it.readText() }
            GlyphSetParser.parse(text).also { logger.i("Hand", "$name parsed in ${android.os.SystemClock.uptimeMillis() - t0} ms") }
        }
    }
}
