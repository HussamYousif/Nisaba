package com.nisaba.magic

import android.content.res.AssetManager
import android.graphics.Typeface

/**
 * Bundled OFL fonts (docs/08 §3). Loaded once in MainActivity.onCreate.
 * - Dancing Script (variable): cursive body — we pin wght 500 (Medium).
 * - Great Vibes: calligraphic headings.
 * - Kalam: handwritten-but-legible labels & dense text.
 */
object Fonts {
    lateinit var dancingScript: Typeface
        private set
    lateinit var dancingScriptBold: Typeface
        private set
    lateinit var greatVibes: Typeface
        private set
    lateinit var kalam: Typeface
        private set
    lateinit var kalamBold: Typeface
        private set

    fun load(assets: AssetManager) {
        dancingScript = Typeface.Builder(assets, "fonts/DancingScript[wght].ttf")
            .setFontVariationSettings("'wght' 500")
            .build()
        dancingScriptBold = Typeface.Builder(assets, "fonts/DancingScript[wght].ttf")
            .setFontVariationSettings("'wght' 700")
            .build()
        greatVibes = Typeface.createFromAsset(assets, "fonts/GreatVibes-Regular.ttf")
        kalam = Typeface.createFromAsset(assets, "fonts/Kalam-Regular.ttf")
        kalamBold = Typeface.createFromAsset(assets, "fonts/Kalam-Bold.ttf")
    }
}
