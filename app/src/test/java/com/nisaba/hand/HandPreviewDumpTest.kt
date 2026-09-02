package com.nisaba.hand

import org.junit.Test
import java.io.File

/**
 * Dev tooling, not a real test: dumps synthesized handwriting geometry to
 * app/build/hand-preview (JSON files) where the Python preview renderer
 * (.tools/hand-preview.py) turns them into PNGs for the vision-model
 * feedback loop used while authoring glyphs (docs/08 §3).
 */
class HandPreviewDumpTest {

    private fun dump(name: String, text: String, widthUnits: Float, seed: Long, variation: Float = 0f, pressure: Boolean = false) {
        val res = Scribe.writeRun(
            text,
            widthUnits,
            seed,
            options = Scribe.Options(variation = variation, slant = if (variation == 0f) 0f else 0.13f, pressure = pressure),
        )
        val sb = StringBuilder()
        sb.append("{\"lines\":[")
        for ((li, line) in res.lines.withIndex()) {
            if (li > 0) sb.append(",")
            sb.append("{\"top\":").append(line.topUnits)
                .append(",\"bottom\":").append(line.bottomUnits)
                .append(",\"pitch\":").append(line.pitchUnits)
                .append(",\"strokes\":[")
            for ((si, s) in line.strokes.withIndex()) {
                if (si > 0) sb.append(",")
                sb.append("[")
                for (i in 0 until s.pointCount) {
                    if (i > 0) sb.append(",")
                    sb.append("[").append(trim(s.xys[i * 2])).append(",").append(trim(s.xys[i * 2 + 1]))
                        .append(",").append(trim(s.widths[i])).append("]")
                }
                sb.append("]")
            }
            sb.append("]}")
        }
        sb.append("]}")
        val dir = File("build/hand-preview")
        dir.mkdirs()
        File(dir, "$name.json").writeText(sb.toString())
    }

    private fun trim(f: Float): String {
        val v = (f * 10).toInt() / 10f
        return if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    }

    @Test
    fun dumpPreviewArtifacts() {
        // The alphabet as separate words (letters shown with entry + exit context)
        dump("alphabet", "a b c d e f g h i j k l m n o p q r s t u v w x y z", 4000f, 1L)
        dump("caps", "A B C D E F G H I J K L M N O P Q R S T U V W X Y Z", 4000f, 2L)
        dump("digits", "0 1 2 3 4 5 6 7 8 9 . , ! ? ' \" - : ; ( ) /", 4000f, 3L)
        dump(
            "pangram",
            "The quick brown fox jumps over the lazy dog. Pack my box with five dozen liquor jugs.",
            1900f, 4L,
        )
        dump("pangramVary", "The quick brown fox jumps over the lazy dog. Pack my box with five dozen liquor jugs.", 1900f, 5L, variation = 1f)
        dump("pangramAB-nopress", "The quick brown fox jumps over the lazy dog. Pack my box with five dozen liquor jugs.", 1900f, 5L, variation = 1f, pressure = false)
        dump(
            "prose",
            "Nisaba is a magical notebook. When you pause, the ink reaches out, and the scribe writes back beneath your hand.",
            1900f, 6L, variation = 1f,
        )
        // individual letters big, for glyph surgery
        for (c in 'a'..'z') {
            dump("letter-$c", "x $c x", 700f, c.code.toLong())
        }
        for (c in 'A'..'Z') {
            dump("cap-$c", "y $c", 700f, (c.code + 100).toLong())
        }
        for (c in "PQIJTfzqsxwkugbmndoeyvhplacir") {
            dump("single-$c", "$c", 500f, (2000L + c.code))
        }
        // problem words from the read-back loop
        for ((i, w) in listOf("Pack", "box", "five", "dozen", "liquor", "jugs", "The", "lazy").withIndex()) {
            dump("word-$i-$w", w, 900f, (1000L + i))
        }
    }
}
